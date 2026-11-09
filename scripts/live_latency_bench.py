#!/usr/bin/env python3
"""Compare builds on a real phone, real Wi-Fi and live provider feeds.

    scripts/live_latency_bench.py snapshot --label baseline
    scripts/live_latency_bench.py run --serial SERIAL --out build/live-latency/night1 --runs 5 --variant baseline --variant head
    scripts/live_latency_bench.py summarize build/live-latency/night1

`snapshot` builds the Debug app and its test APK from the current checkout and keeps them under a label. `run`
installs each variant in turn over the phone's Debug app, keeping its data and accounts, and runs
`LiveLatencyBenchmarkTest` once per case, each in a fresh process so no connection survives from the previous case.
Variants swap order every run, so they share the phone's thermal and network conditions. Before each variant it
records whether IPv6 and IPv4 work from the phone, since that decides how much the connection fixes can show.

Only `com.theoriacodex.debug` and its test package are installed, after `scripts/verify_device_apk.py` proves each
APK. The test reports numbers only: no screenshots, recordings or media are captured.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APKS = ROOT / "build" / "live-latency" / "apks"
APP_APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
DEBUG_PACKAGE = "com.theoriacodex.debug"
TEST_PACKAGE = "com.theoriacodex.debug.test"
TEST_CLASS = "com.theoriacodex.app.live.LiveLatencyBenchmarkTest"
RUNNER = f"{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
STATUS = re.compile(r"INSTRUMENTATION_STATUS: theoriaLatency=(\{.*\})")
SOURCES = (
    "GELBOORU", "RULE34XXX", "DANBOORU", "AIBOORU", "HITOMI", "NHENTAI", "PIXIV", "IWARA",
    "RULE34PAHEAL", "RULE34VIDEO", "RULE34GEN",
)
CASES = tuple(f"source:{source}" for source in SOURCES) + ("unified:cold", "unified:warm")
CASE_TIMEOUT_S = 300
DUAL_STACK_PROBES = ("danbooru.donmai.us", "ltn.gold-usergeneratedcontent.net", "i.iwara.tv", "api.rule34.xxx")


def run(*command: str, timeout: float | None = None, check: bool = True) -> str:
    done = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=timeout, check=False)
    if check and done.returncode != 0:
        raise RuntimeError(f"{' '.join(command)} failed:\n{done.stdout}\n{done.stderr}")
    return done.stdout


def verify(apk: Path, package: str) -> None:
    run(sys.executable, "scripts/verify_device_apk.py", str(apk), "--package", package)


def snapshot(label: str) -> None:
    run("./gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--console=plain", "--quiet")
    verify(APP_APK, DEBUG_PACKAGE)
    verify(TEST_APK, TEST_PACKAGE)
    destination = APKS / label
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(APP_APK, destination / "app.apk")
    shutil.copy2(TEST_APK, destination / "test.apk")
    revision = run("git", "rev-parse", "HEAD").strip()
    dirty = bool(run("git", "status", "--porcelain").strip())
    (destination / "build.json").write_text(json.dumps({"revision": revision, "dirty": dirty}, indent=2))
    print(f"{label}: {revision}{' (dirty)' if dirty else ''}")


class Phone:
    def __init__(self, serial: str) -> None:
        self.serial = serial

    def adb(self, *arguments: str, timeout: float = 120, check: bool = True) -> str:
        return run("adb", "-s", self.serial, *arguments, timeout=timeout, check=check)

    def shell(self, command: str, timeout: float = 60) -> str:
        return self.adb("shell", command, timeout=timeout, check=False)

    def require_physical(self) -> None:
        if self.shell("getprop ro.kernel.qemu").strip() == "1":
            raise RuntimeError("This benchmark needs the physical phone")

    def install(self, variant: str) -> None:
        apks = APKS / variant
        verify(apks / "app.apk", DEBUG_PACKAGE)
        verify(apks / "test.apk", TEST_PACKAGE)
        self.adb("install", "-r", "-t", str(apks / "app.apk"), timeout=300)
        self.adb("install", "-r", "-t", str(apks / "test.apk"), timeout=300)

    def measure(self, case: str) -> dict:
        output = self.adb(
            "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS, "-e", "theoriaLiveLatency", "true",
            "-e", "theoriaCase", case, RUNNER, timeout=CASE_TIMEOUT_S, check=False,
        )
        match = STATUS.search(output)
        if match is None:
            tail = "\n".join(output.splitlines()[-12:])
            return {"case": case, "harness_failure": tail}
        return json.loads(match.group(1))

    def connect_ms(self, family: str, host: str) -> float | None:
        fields = "%{time_connect} %{exitcode}"
        output = self.shell(f"curl -{family} -sI -o /dev/null --connect-timeout 4 -m 6 -w '{fields}' https://{host}/")
        parts = output.split()
        if len(parts) != 2 or parts[1] != "0":
            return None
        return round(float(parts[0]) * 1000)

    def environment(self) -> dict:
        ping = self.shell("ping -c 8 -i 0.3 -W 3 1.1.1.1 | tail -1")
        thermal = self.shell("dumpsys thermalservice | grep -m1 'Thermal Status'")
        temperature = self.shell("dumpsys battery | grep -m1 temperature")
        return {
            "at": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "ipv4_connect_ms": {host: self.connect_ms("4", host) for host in DUAL_STACK_PROBES},
            "ipv6_connect_ms": {host: self.connect_ms("6", host) for host in DUAL_STACK_PROBES},
            "ping_summary": ping.strip(),
            "thermal": thermal.strip(),
            "battery": temperature.strip(),
        }


def run_benchmark(arguments: argparse.Namespace) -> None:
    phone = Phone(arguments.serial)
    phone.require_physical()
    out = ROOT / arguments.out if not Path(arguments.out).is_absolute() else Path(arguments.out)
    cases = tuple(arguments.case) or CASES
    for index in range(1, arguments.runs + 1):
        order = arguments.variant if index % 2 else list(reversed(arguments.variant))
        for variant in order:
            directory = out / variant
            directory.mkdir(parents=True, exist_ok=True)
            print(f"[{time.strftime('%H:%M:%S')}] run {index}/{arguments.runs} {variant}", flush=True)
            phone.install(variant)
            environment = phone.environment()
            with (directory / "environment.jsonl").open("a") as stream:
                stream.write(json.dumps({"run": index, **environment}) + "\n")
            for case in cases:
                report = {"run": index, **phone.measure(case)}
                with (directory / "results.jsonl").open("a") as stream:
                    stream.write(json.dumps(report) + "\n")
                print(f"  {case}: {compact(report)}", flush=True)
                time.sleep(arguments.pause)


def compact(report: dict) -> str:
    if "harness_failure" in report:
        return "HARNESS FAILURE"
    if report.get("unavailable"):
        return "unavailable"
    if report.get("search_ok") is False:
        return f"failed {report.get('failure')} after {report.get('search_ms')} ms"
    keys = ("search_ms", "cards_all_ms", "cards_rx", "pages_all_ms", "first_card_ms", "first_image_ms", "screen_images_ms")
    return " ".join(f"{key}={report[key]}" for key in keys if key in report)


def summarize(directory: str) -> None:
    root = ROOT / directory if not Path(directory).is_absolute() else Path(directory)
    summary: dict[str, dict] = {}
    for variant in sorted(path for path in root.iterdir() if (path / "results.jsonl").exists()):
        reports = [json.loads(line) for line in (variant / "results.jsonl").read_text().splitlines()]
        cases: dict[str, list[dict]] = {}
        for report in reports:
            cases.setdefault(report["case"], []).append(report)
        summary[variant.name] = {case: summarize_case(items) for case, items in sorted(cases.items())}
    (root / "summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))


def summarize_case(reports: list[dict]) -> dict:
    metrics: dict[str, object] = {"samples": len(reports)}
    failures = [report for report in reports if report.get("search_ok") is False or "harness_failure" in report]
    metrics["failed_samples"] = len(failures)
    reasons = sorted({str(report.get("failure", "harness")) for report in failures})
    if reasons:
        metrics["failure_kinds"] = reasons
    names = {name for report in reports for name, value in report.items() if is_number(value) and name != "run"}
    for name in sorted(names):
        values = [report[name] for report in reports if is_number(report.get(name))]
        metrics[name] = {"median": statistics.median(values), "min": min(values), "max": max(values), "n": len(values)}
    return metrics


def is_number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    snapshot_command = commands.add_parser("snapshot")
    snapshot_command.add_argument("--label", required=True)
    run_command = commands.add_parser("run")
    run_command.add_argument("--serial", required=True)
    run_command.add_argument("--out", required=True)
    run_command.add_argument("--runs", type=int, default=3)
    run_command.add_argument("--variant", action="append", required=True)
    run_command.add_argument("--case", action="append", default=[])
    run_command.add_argument("--pause", type=float, default=2.0)
    summarize_command = commands.add_parser("summarize")
    summarize_command.add_argument("directory")
    arguments = parser.parse_args()
    if arguments.command == "snapshot":
        snapshot(arguments.label)
    elif arguments.command == "run":
        run_benchmark(arguments)
    else:
        summarize(arguments.directory)


if __name__ == "__main__":
    main()
