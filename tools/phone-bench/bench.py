#!/usr/bin/env python3
"""Drive the :vision-bench app on one device over adb. Host-side only; reads text, never the screen.

  bench.py --serial S --target phone|emulator preflight   # APK id + certificate, device identity
  bench.py --serial S --target ... setup                   # install the bench APK, push models and inputs
  bench.py --serial S --target ... run NAME key=value ...  # one run (see RunSpec in Bench.kt)
  bench.py --serial S --target ... matrix [--only PREFIX]  # the cold matrix below
  bench.py --serial S --target ... cleanup                 # stop and uninstall the bench package only

Every adb command names the serial. Only com.theoriacodex.visionbench is installed, started, stopped
or uninstalled; no setting is changed (the app keeps its own window awake). Results land in
results/<target>/<run>.json plus parity .bin files. Files move in and out of the app's internal
files/ with `run-as` (the app is debuggable), since apps cannot read files adb writes to their
external folder.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
PACKAGE = "com.theoriacodex.visionbench"
ACTIVITY = f"{PACKAGE}/.BenchActivity"
APK = REPO / "vision-bench/build/outputs/apk/debug/vision-bench-debug.apk"

# Cold matrix: each run is a fresh process. Slow CPU configs skip the exact-parity pass (threads do
# not change numerics) and take only the minimum image count.
MATRIX = [
    ("wd_int8_cpu4", dict(model="wd_vit.int8.tflite", kind="wd", acc="cpu", threads=4)),
    ("wd_int8_cpu2", dict(model="wd_vit.int8.tflite", kind="wd", acc="cpu", threads=2, parity=False, seconds=30)),
    ("wd_int8_cpu1", dict(model="wd_vit.int8.tflite", kind="wd", acc="cpu", threads=1, parity=False, seconds=30, min_images=32)),
    ("wd_fp16_cpu4", dict(model="wd_vit.fp16.tflite", kind="wd", acc="cpu", threads=4, seconds=30)),
    # GPU and NPU runs are strict (no automatic CPU fallback), so a number is never a disguised CPU run.
    ("wd_int8_gpu", dict(model="wd_vit.int8.tflite", kind="wd", acc="gpu", strict=True, gpu_quant_ops=True)),
    ("wd_fp16_gpu", dict(model="wd_vit.fp16.tflite", kind="wd", acc="gpu", strict=True)),
    ("wd_fp16_gpu32", dict(model="wd_vit.fp16.tflite", kind="wd", acc="gpu", strict=True, precision="fp32")),
    ("wd_int8_npu", dict(model="wd_vit.int8.tflite", kind="wd", acc="npu", strict=True)),
    ("wd_fp16_npu", dict(model="wd_vit.fp16.tflite", kind="wd", acc="npu", strict=True)),
    ("dino_int8_cpu4", dict(model="dinov2_s.int8.tflite", kind="dino", acc="cpu", threads=4)),
    ("dino_int8_cpu2", dict(model="dinov2_s.int8.tflite", kind="dino", acc="cpu", threads=2, parity=False, seconds=30)),
    ("dino_int8_cpu1", dict(model="dinov2_s.int8.tflite", kind="dino", acc="cpu", threads=1, parity=False, seconds=30)),
    ("dino_int8_gpu", dict(model="dinov2_s.int8.tflite", kind="dino", acc="gpu", strict=True, gpu_quant_ops=True)),
    ("dino_fp16_gpu", dict(model="dinov2_s.fp16.tflite", kind="dino", acc="gpu", strict=True)),
    ("dino_int8_npu", dict(model="dinov2_s.int8.tflite", kind="dino", acc="npu", strict=True)),
    ("dino_fp16_npu", dict(model="dinov2_s.fp16.tflite", kind="dino", acc="npu", strict=True)),
]


class Device:
    def __init__(self, serial: str, target: str):
        self.serial, self.target = serial, target
        self.out = HERE / "results" / target
        self.out.mkdir(parents=True, exist_ok=True)

    def adb(self, *args: str, check: bool = True, timeout: float = 600) -> str:
        done = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        if check and done.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {done.stdout[-500:]} {done.stderr[-500:]}")
        return done.stdout.strip()

    def shell(self, command: str, check: bool = True) -> str:
        return self.adb("shell", command, check=check)

    def preflight(self) -> None:
        """Host check of the APK, then read-only checks that the serial is the intended device."""
        subprocess.run([sys.executable, str(REPO / "scripts/verify_device_apk.py"), str(APK), "--package", PACKAGE], check=True)
        certs = subprocess.run([apksigner(), "verify", "--print-certs", str(APK)], capture_output=True, text=True, check=True).stdout
        print("APK signer:", re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", certs))
        qemu = self.shell("getprop ro.kernel.qemu", check=False)
        if (self.target == "phone") == (qemu == "1"):
            raise SystemExit(f"refusing: target={self.target} but ro.kernel.qemu={qemu!r}")
        for prop in ("ro.product.model", "ro.soc.manufacturer", "ro.soc.model", "ro.build.version.release"):
            print(prop, "=", self.shell(f"getprop {prop}", check=False))
        listed = self.shell("pm list packages com.theoriacodex", check=False).split()
        print("installed theoriacodex packages:", listed or "none")
        print("battery:", self.battery())

    def app(self, command: str, check: bool = True) -> str:
        """A shell command run as the bench app, in its data directory."""
        return self.adb("shell", f"run-as {PACKAGE} sh -c '{command}'", check=check)

    def stream_in(self, local_stream, command: str) -> None:
        done = subprocess.run(["adb", "-s", self.serial, "exec-in", "run-as", PACKAGE, "sh", "-c", command],
                              stdin=local_stream, capture_output=True, timeout=1800)
        if done.returncode != 0:
            raise RuntimeError(f"exec-in {command} failed: {done.stderr[-500:]!r}")

    def setup(self) -> None:
        self.adb("install", "-r", "-t", str(APK), timeout=900)
        self.app("mkdir -p files/models files/inputs files/results")
        for path in sorted((HERE / "models").glob("*.tflite")):
            with path.open("rb") as f:
                self.stream_in(f, f"cat > files/models/{path.name}")
        tar = subprocess.Popen(["tar", "c", "-C", str(HERE / "inputs"), "jpeg", "wd448", "dino224"], stdout=subprocess.PIPE)
        self.stream_in(tar.stdout, "tar x -C files/inputs")
        tar.wait()
        print(self.app("du -s -k files/models files/inputs; ls -l files/models"))

    def pull(self, remote: str, local: Path) -> None:
        with local.open("wb") as f:
            subprocess.run(["adb", "-s", self.serial, "exec-out", "run-as", PACKAGE, "cat", remote], stdout=f, check=True, timeout=600)

    def battery(self) -> dict[str, str]:
        text = self.shell("dumpsys battery", check=False)
        keys = ("level", "temperature", "status", "AC powered", "USB powered", "Wireless powered")
        return {k: m.group(1) for k in keys if (m := re.search(rf"^\s*{k}: (.+)$", text, re.M))}

    def thermal_status(self) -> int:
        match = re.search(r"Thermal Status: (\d+)", self.shell("dumpsys thermalservice", check=False))
        return int(match.group(1)) if match else -1

    def cooldown(self, max_temp_c: float, max_wait_s: int) -> None:
        """Wait (read-only) until battery temperature and thermal status settle, up to max_wait_s."""
        start = time.time()
        while True:
            temp = int(self.battery().get("temperature", "0")) / 10
            status = self.thermal_status()
            if (temp <= max_temp_c and status <= 0) or time.time() - start > max_wait_s:
                print(f"  cooldown done after {time.time() - start:.0f}s: battery {temp} C, thermal status {status}", flush=True)
                return
            time.sleep(15)

    def run(self, name: str, params: dict[str, object], timeout_s: int = 1800) -> Path | None:
        self.adb("shell", "am", "force-stop", PACKAGE)
        self.app(f"rm -f files/results/{name}.*")
        extras: list[str] = []
        for key, value in {"run": name, **params}.items():
            flag = {bool: "--ez", int: "--ei"}.get(type(value), "--es")
            extras += [flag, key, str(value).lower() if isinstance(value, bool) else str(value)]
        print(f"> {name} {params}", flush=True)
        since = self.shell("date '+%m-%d %H:%M:%S.000'")
        self.adb("shell", "am", "start", "-W", "-n", ACTIVITY, *extras)
        start, seen_process, pids = time.time(), False, set()
        while time.time() - start < timeout_s:
            time.sleep(5)
            pids.update(self.shell(f"pidof {PACKAGE}", check=False).split())
            if self.app(f"ls files/results/{name}.json", check=False).endswith(".json"):
                break
            alive = bool(self.shell(f"pidof {PACKAGE}", check=False))
            seen_process |= alive
            if seen_process and not alive:
                print(f"  {name}: process exited without results", flush=True)
                break
        else:
            print(f"  {name}: timed out", flush=True)
        self.adb("shell", "am", "force-stop", PACKAGE)
        # LiteRT's own lines say where ops ran (accelerator, partitions, CPU fallback) and why loads failed.
        log = self.adb("logcat", "-d", "-T", since, check=False).splitlines()
        pids |= {line.split()[2] for line in log if f" {name} " in line and "VisionBench" in line and len(line.split()) > 2}
        keep = [line for line in log if len(line.split()) > 2 and line.split()[2] in pids
                and re.search(r" (litert|tflite|native|VisionBench|qnn\w*)\s*:", line, re.I)]
        (self.out / f"{name}.log").write_text("\n".join(keep) + "\n")
        pulled = None
        for suffix in (".json", ".exact.bin", ".e2e.bin"):
            remote = f"files/results/{name}{suffix}"
            if self.app(f"ls {remote}", check=False).endswith(suffix):
                self.pull(remote, self.out / f"{name}{suffix}")
                pulled = pulled or self.out / f"{name}{suffix}"
        if pulled:
            summarize(self.out / f"{name}.json")
        return pulled

    def cleanup(self) -> None:
        self.adb("shell", "am", "force-stop", PACKAGE, check=False)
        if PACKAGE in self.shell(f"pm list packages {PACKAGE}", check=False):
            print(self.adb("uninstall", PACKAGE))  # the isolated bench package only; removes its files


def apksigner() -> str:
    sdk = Path((REPO / "local.properties").read_text().split("sdk.dir=")[1].split()[0])
    return str(sorted((sdk / "build-tools").glob("*/apksigner"))[-1])


def summarize(path: Path) -> None:
    import json

    r = json.loads(path.read_text())
    e2e = r.get("e2e", {}).get("summary", {})
    fmt = lambda s: f"{s.get('median', float('nan')):.1f}/{s.get('p90', float('nan')):.1f}" if s else "-"
    print(f"  load {r.get('load_ms', float('nan')):.0f} ms, first {r.get('first_inference_ms', float('nan')):.0f} ms, "
          f"infer med/p90 {fmt(e2e.get('infer'))}, e2e {fmt(e2e.get('total'))} (n={e2e.get('n', 0)}), "
          f"peak PSS {r.get('peak_pss_kb', 0) / 1024:.0f} MB, accelerators {r.get('available_accelerators')}"
          + (f"\n  ERROR {r['error'].splitlines()[0][:300]}" if "error" in r else ""), flush=True)


def parse_value(raw: str) -> object:
    if raw in ("true", "false"):
        return raw == "true"
    return int(raw) if raw.lstrip("-").isdigit() else raw


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--target", choices=("phone", "emulator"), required=True)
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("preflight")
    sub.add_parser("setup")
    sub.add_parser("cleanup")
    matrix = sub.add_parser("matrix")
    matrix.add_argument("--only", default="")
    matrix.add_argument("--cool-to", type=float, default=36.0, help="battery temperature to wait for between runs")
    matrix.add_argument("--cool-max", type=int, default=240)
    one = sub.add_parser("run")
    one.add_argument("name")
    one.add_argument("params", nargs="*")
    args = parser.parse_args()

    device = Device(args.serial, args.target)
    match args.cmd:
        case "preflight":
            device.preflight()
        case "setup":
            device.setup()
        case "cleanup":
            device.cleanup()
        case "run":
            params = {k: parse_value(v) for k, v in (p.split("=", 1) for p in args.params)}
            timeout = int(params.get("seconds", 90)) + 1200
            device.run(args.name, params, timeout_s=timeout)
        case "matrix":
            for name, params in MATRIX:
                if name.startswith(args.only):
                    if args.target == "phone":
                        device.cooldown(args.cool_to, args.cool_max)
                    device.run(name, params)


if __name__ == "__main__":
    main()
