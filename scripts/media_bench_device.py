#!/usr/bin/env python3
"""Run the media buffering benchmark on a device and summarize it.

    scripts/media_bench_device.py run --serial R5CWC0SXR3A --label baseline [--runs 3]
    scripts/media_bench_device.py run --serial R5CWC0SXR3A --label ab --skip-build \
        --variant base=build/media-bench/apks/base --variant head=build/media-bench/apks/head
    scripts/media_bench_device.py record --round 0 build/media-bench/baseline/results.json

`run` generates the test media (ffmpeg), builds the debug and test APKs, proves both are the isolated debug
lane (`scripts/verify_device_apk.py`) before installing them, copies the media into the debug app's
`cache/media-bench` (through `run-as`), and runs each `MediaBufferingBenchmarkTest` scenario once per run in a
fresh app process. The test serves that media from a throttled loopback server inside the app process. The
summary is the median of every metric across runs. With `--variant NAME=DIR` (APK pairs saved from earlier
builds), each run installs and measures every variant in turn, swapping their order every run, so variants share
the phone's thermal and network conditions; one results file is written per variant. `record` writes a summary into
`.docs/exec/media-buffering-metrics.json` as one round entry.

Only `com.theoriacodex.debug` and its test package are installed; no provider media is involved.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MEDIA = ROOT / "build" / "media-bench" / "media"
METRICS_FILE = ROOT / ".docs" / "exec" / "media-buffering-metrics.json"
APP_APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
DEBUG_PACKAGE = "com.theoriacodex.debug"
TEST_CLASS = "com.theoriacodex.app.viewer.MediaBufferingBenchmarkTest"
RUNNER = "com.theoriacodex.debug.test/androidx.test.runner.AndroidJUnitRunner"
SCENARIOS = ("feedToViewer", "viewerSeekAndScrub", "galleryPaging", "viewerSwipe", "feedScroll")
DEVICE_MEDIA = "cache/media-bench"
STATUS_PREFIX = "INSTRUMENTATION_STATUS: mediaBench="

# name -> (unit, what one sample measures). Lower is better unless the name says otherwise.
METRICS = {
    "feed_preview_ttff_ms": ("ms", "feed card lease to first rendered frame, median of 4 simultaneous cards"),
    "viewer_ttff_from_feed_ms": ("ms", "Viewer lease to first frame for a clip a feed card was playing"),
    "viewer_time_to_playing_from_feed_ms": ("ms", "Viewer lease to isPlaying for that clip"),
    "viewer_rebuffer_count": ("count", "rebuffers while the clip loops twice in the Viewer"),
    "viewer_rebuffer_ms": ("ms", "time spent rebuffering while the clip loops twice"),
    "clip_bytes_per_view_mb": ("MB", "bytes served for the clip: feed autoplay plus two Viewer loops (file 7.3 MB)"),
    "clip_duplicate_mb": ("MB", "bytes of that clip served more than once (re-downloaded ranges)"),
    "feed_bytes_after_viewer_open_mb": ("MB", "bytes the other three feed clips kept downloading after the Viewer opened"),
    "feed_return_all_playing_ms": ("ms", "Viewer closed: four cards lease again until all four are playing"),
    "feed_resume_after_short_viewer_ms": ("ms", "Viewer opened and closed within the release grace: stopped cards resume until all play"),
    "viewer_ttff_cold_ms": ("ms", "Viewer lease to first frame for a 90 s video nothing had loaded"),
    "seek_to_resume_ms": ("ms", "seek past the buffer to playing again, median of 4 seeks"),
    "seek_to_resume_max_ms": ("ms", "slowest of those 4 seeks"),
    "scrub_first_frame_ms": ("ms", "drag start to its first preview frame, past the buffer, through the page's scrub previews"),
    "scrub_frame_ms": ("ms", "moving to a target past the buffer to its preview frame, median of the drag's 3 targets"),
    "scrub_bytes_mb": ("MB", "bytes downloaded to show those 3 preview frames"),
    "seek_after_scrub_ms": ("ms", "release seek to the last previewed position until playing"),
    "scrub_second_drag_first_frame_ms": ("ms", "a second drag on the same page, after the release seek settles, to its first frame"),
    "long_bytes_mb": ("MB", "bytes served for the 90 s video over the seek and scrub session"),
    "long_duplicate_mb": ("MB", "bytes of the 90 s video served more than once"),
    "gallery_open_to_first_page_ms": ("ms", "Viewer session start to page 1 decoded at screen size"),
    "page_load_p50_ms": ("ms", "page becomes current to decoded, median while reading 40 pages at 1.2 s each"),
    "page_load_p90_ms": ("ms", "90th percentile of the same"),
    "pages_ready_ahead": ("pages", "of the next 10 pages, how many are on disk when a page opens (higher is better)"),
    "skim_shown_pct": ("%", "pages shown within 200 ms while skimming 35 pages (higher is better)"),
    "skim_shown_p50_ms": ("ms", "median time to show the skimmed pages that made it"),
    "peak_memory_delta_mb": ("MB", "peak Java plus native heap growth while paging"),
    "peak_memory_mb": ("MB", "peak Java plus native heap while paging"),
    "gallery_bytes_mb": ("MB", "bytes served for gallery pages over the session"),
    "gallery_duplicate_mb": ("MB", "gallery page bytes served more than once"),
    "swipe_ttff_ms": ("ms", "swipe start to the neighbour video's first frame (three 90 s videos, 20 s on the middle one)"),
    "swipe_time_to_playing_ms": ("ms", "swipe start until the neighbour video plays"),
    "swipe_rebuffer_count": ("count", "rebuffers in the 3 s after the swipe"),
    "swipe_target_bytes_before_mb": ("MB", "bytes of the swiped-to video downloaded before the swipe (prefetch)"),
    "swipe_unvisited_neighbour_mb": ("MB", "bytes of the neighbour never visited"),
    "swipe_session_mb": ("MB", "bytes of all three videos over the swipe session"),
    "feed_scroll_ttff_ms": ("ms", "scroll start to the first frame of the two cards that came on screen, median"),
    "feed_scroll_all_playing_ms": ("ms", "scroll start until all four visible cards play"),
    "feed_scrolled_past_mb": ("MB", "bytes the two scrolled-past clips (2 s on screen) downloaded in the 8 s after leaving it"),
    "feed_scroll_back_playing_ms": ("ms", "scrolling back until the two returning cards play again"),
    "feed_scroll_session_mb": ("MB", "bytes of all six clips over the scroll session"),
}


def generate_media() -> None:
    """Deterministic H.264/AAC clips and JPEG pages, generated once into the build directory."""
    MEDIA.mkdir(parents=True, exist_ok=True)
    gallery = MEDIA / "gallery"
    gallery.mkdir(exist_ok=True)
    jobs = []
    for index, hue in zip(range(1, 7), (0, 90, 180, 270, 135, 315)):
        jobs.append((MEDIA / f"clip_{index}.mp4", video_args(15, hue, keyframe_seconds=1)))
    for index, hue in zip(range(1, 4), (45, 225, 300)):
        jobs.append((MEDIA / f"long_{index}.mp4", video_args(90, hue, keyframe_seconds=2)))
    for output, args in jobs:
        if not output.exists():
            run(["ffmpeg", "-loglevel", "error", "-y", *args, str(output)])
    if not (gallery / "page_080.jpg").exists():
        run([
            "ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=1280x1808:r=1:d=80",
            "-vf", "noise=alls=10:allf=t", "-q:v", "4", "-start_number", "1", str(gallery / "page_%03d.jpg"),
        ])


def video_args(seconds: int, hue: int, keyframe_seconds: int) -> list[str]:
    gop = str(30 * keyframe_seconds)
    return [
        "-f", "lavfi", "-i", f"testsrc2=s=1920x1080:r=30:d={seconds}",
        "-f", "lavfi", "-i", f"sine=f=440:d={seconds}",
        "-vf", f"noise=alls=8:allf=t,hue=h={hue}",
        "-c:v", "libx264", "-preset", "veryfast", "-b:v", "4M", "-maxrate", "4M", "-bufsize", "4M",
        "-g", gop, "-keyint_min", gop, "-sc_threshold", "0", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart",
    ]


def run(command: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(command, check=True, cwd=ROOT, **kwargs)


def adb(serial: str, *args: str, capture: bool = False) -> str:
    result = subprocess.run(["adb", "-s", serial, *args], check=True, text=True, capture_output=capture)
    return result.stdout if capture else ""


def build() -> None:
    run(["./gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--console=plain", "-q"])


def install(serial: str, apk_dir: Path | None = None) -> None:
    app_apk = apk_dir / APP_APK.name if apk_dir else APP_APK
    test_apk = apk_dir / TEST_APK.name if apk_dir else TEST_APK
    for apk, package in ((app_apk, DEBUG_PACKAGE), (test_apk, f"{DEBUG_PACKAGE}.test")):
        run([sys.executable, "scripts/verify_device_apk.py", str(apk), "--package", package])
        adb(serial, "install", "-r", "-t", str(apk))


def push_media(serial: str) -> None:
    """Copy the media into the debug app's cache once; a manifest of names and sizes detects changes."""
    files = sorted(path for path in MEDIA.rglob("*") if path.is_file())
    manifest = "\n".join(f"{path.relative_to(MEDIA)} {path.stat().st_size}" for path in files)
    remote = subprocess.run(
        ["adb", "-s", serial, "shell", f"run-as {DEBUG_PACKAGE} cat {DEVICE_MEDIA}/manifest.txt"],
        text=True, capture_output=True,
    ).stdout.strip()
    if remote == manifest:
        return
    (MEDIA / "manifest.txt").write_text(manifest)
    archive = subprocess.Popen(["tar", "-c", "-C", str(MEDIA), "."], stdout=subprocess.PIPE)
    subprocess.run(
        ["adb", "-s", serial, "exec-in", "run-as", DEBUG_PACKAGE, "sh", "-c",
         f"rm -rf {DEVICE_MEDIA} && mkdir -p {DEVICE_MEDIA} && tar -x -C {DEVICE_MEDIA}"],
        stdin=archive.stdout, check=True,
    )
    archive.wait()
    (MEDIA / "manifest.txt").unlink()


def instrument(serial: str, scenario: str, rate_mbps: float, ttfb_ms: int, swipe_watch_ms: int | None = None) -> dict:
    extras = ["-e", "mediaBenchSwipeWatchMs", str(swipe_watch_ms)] if swipe_watch_ms is not None else []
    output = adb(
        serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", f"{TEST_CLASS}#{scenario}",
        "-e", "mediaBench", "true",
        "-e", "mediaBenchRateMbps", str(rate_mbps),
        "-e", "mediaBenchTtfbMs", str(ttfb_ms),
        *extras,
        RUNNER, capture=True,
    )
    payloads = [line[len(STATUS_PREFIX):] for line in output.splitlines() if line.startswith(STATUS_PREFIX)]
    if not payloads:
        tail = "\n".join(output.splitlines()[-40:])
        raise RuntimeError(f"{scenario} reported no result:\n{tail}")
    return json.loads(payloads[-1])


def summarize(samples: list[dict]) -> dict:
    summary = {}
    for name, (unit, method) in METRICS.items():
        values = [sample[name] for sample in samples if isinstance(sample.get(name), (int, float))]
        if values:
            summary[name] = {
                "value": round(statistics.median(values), 2),
                "unit": unit,
                "n": len(values),
                "values": values,
                "method": method,
            }
    return summary


def git_head() -> str:
    return subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, text=True, capture_output=True).stdout.strip()


def command_run(args: argparse.Namespace) -> None:
    generate_media()
    if not args.skip_build:
        build()
    variants = {name: Path(path) for name, path in (item.split("=", 1) for item in args.variant)}
    if not variants and not args.skip_install:
        install(args.serial)
    push_media(args.serial)
    samples = {name: {scenario: [] for scenario in args.scenarios} for name in variants or {"current": None}}
    for run_index in range(1, args.runs + 1):
        # The phone warms through a run, so whichever variant goes second is a little slower: swap every run.
        order = list(samples) if run_index % 2 else list(reversed(samples))
        for name in order:
            if name in variants:
                install(args.serial, variants[name])
            for scenario in args.scenarios:
                result = instrument(args.serial, scenario, args.rate_mbps, args.ttfb_ms, args.swipe_watch_ms)
                samples[name][scenario].append(result["metrics"])
                printable = {k: v for k, v in result["metrics"].items() if k not in ("server", "diagnostics")}
                print(f"run {run_index} {name} {scenario}: {json.dumps(printable)}", flush=True)
    for name, variant_samples in samples.items():
        out = ROOT / "build" / "media-bench" / args.label / ("" if name == "current" else name)
        out.mkdir(parents=True, exist_ok=True)
        per_run = [
            {k: v for scenario_runs in variant_samples.values() for k, v in scenario_runs[index].items()}
            for index in range(args.runs)
        ]
        results = {
            "label": args.label if name == "current" else f"{args.label}/{name}",
            "commit": git_head() if name == "current" else str(variants[name]),
            "device": adb(args.serial, "shell", "getprop", "ro.product.model", capture=True).strip(),
            "network": {"rate_mbps": args.rate_mbps, "ttfb_ms": args.ttfb_ms, "transport": "in-process loopback"},
            "swipe_watch_ms": args.swipe_watch_ms,
            "runs": args.runs,
            "interleaved_with": [other for other in samples if other != name],
            "summary": summarize(per_run),
            "samples": variant_samples,
        }
        (out / "results.json").write_text(json.dumps(results, indent=2) + "\n")
        print(f"wrote {out / 'results.json'}")
        for metric, entry in results["summary"].items():
            print(f"  {metric:38s} {entry['value']:>10} {entry['unit']:6s} n={entry['n']} {entry['values']}")


def command_record(args: argparse.Namespace) -> None:
    results = json.loads(args.results.read_text())
    history = json.loads(METRICS_FILE.read_text()) if METRICS_FILE.exists() else []
    history = [entry for entry in history if entry.get("round") != args.round]
    network = results["network"]
    interleaved = results.get("interleaved_with") or []
    method_prefix = f"interleaved run by run with {', '.join(interleaved)}; " if interleaved else ""
    history.append({
        "round": args.round,
        "commit": args.commit or results["commit"],
        "device": results["device"],
        "conditions": f"{network['rate_mbps']} Mbit/s shared, {network['ttfb_ms']} ms TTFB, {network['transport']}",
        "metrics": {
            name: {
                "value": entry["value"],
                "unit": entry["unit"],
                "n": entry["n"],
                "method": f"{method_prefix}median of {entry['n']} runs; {entry['method']}",
            }
            for name, entry in results["summary"].items()
        },
    })
    history.sort(key=lambda entry: entry["round"])
    METRICS_FILE.write_text(json.dumps(history, indent=2) + "\n")
    print(f"recorded round {args.round} in {METRICS_FILE.relative_to(ROOT)}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    run_parser = commands.add_parser("run")
    run_parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"), required="ANDROID_SERIAL" not in os.environ)
    run_parser.add_argument("--label", required=True)
    run_parser.add_argument("--runs", type=int, default=3)
    run_parser.add_argument("--scenarios", nargs="+", choices=SCENARIOS, default=list(SCENARIOS))
    run_parser.add_argument("--rate-mbps", type=float, default=16.0)
    run_parser.add_argument("--ttfb-ms", type=int, default=100)
    run_parser.add_argument("--swipe-watch-ms", type=int, help="how long viewerSwipe plays the middle video (default 20000)")
    run_parser.add_argument("--skip-build", action="store_true")
    run_parser.add_argument("--skip-install", action="store_true")
    run_parser.add_argument("--variant", action="append", default=[], metavar="NAME=DIR",
                            help="interleave saved APK pairs (app-debug.apk, app-debug-androidTest.apk) per run")
    record_parser = commands.add_parser("record")
    record_parser.add_argument("--round", type=int, required=True)
    record_parser.add_argument("--commit")
    record_parser.add_argument("results", type=Path)
    args = parser.parse_args()
    {"run": command_run, "record": command_record}[args.command](args)


if __name__ == "__main__":
    main()
