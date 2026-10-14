#!/usr/bin/env python3
"""Preflight and test a native download enqueue gap across two isolated Debug processes."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import threading
import uuid

from verify_device_apk import sdk_directory, verify

ROOT = Path(__file__).resolve().parents[1]
TEST_CLASS = "com.theoriacodex.app.media.PostDownloadProcessRecoveryDeviceTest"
RUNNER = "com.theoriacodex.debug.test/androidx.test.runner.AndroidJUnitRunner"


@contextmanager
def fixture_server(evidence: Path):
    release = threading.Event()
    log_lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            page = self.path.split("?")[0].strip("/").removesuffix(".png")
            if page not in {"0", "1", "2", "3"}:
                self.send_error(404)
                return
            with log_lock, (evidence / "requests.log").open("a") as log:
                log.write(f"page={page} range={self.headers.get('Range')}\n")
            if page == "1" and not release.wait(180):
                self.send_error(503)
                return
            data = f"native-recovery-page-{page}".encode()
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        yield server.server_port, release
    finally:
        release.set()
        server.shutdown()
        server.server_close()
        worker.join(5)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--evidence", type=Path, required=True, help="A new directory; existing evidence is never replaced")
    args = parser.parse_args()
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    adb = str(sdk_directory() / "platform-tools/adb")
    inventory = subprocess.check_output([adb, "devices"], text=True)
    connected = [line.split()[0] for line in inventory.splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == "device"]
    if connected != [args.serial]:
        raise SystemExit("Exactly one selected authorized device must be connected")

    def run(command: list[str], log_name: str) -> None:
        with (evidence / log_name).open("w") as log:
            subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)

    gradle = [str(ROOT / "gradlew")]
    selector = f"-Pandroid.testInstrumentationRunnerArguments.class={TEST_CLASS}"
    run(gradle + [":app:connectedDebugAndroidTest", selector, "--dry-run", "--offline", "--no-parallel", "--console=plain"], "task-graph.txt")
    run(gradle + [":app:assembleDebug", ":app:assembleDebugAndroidTest", "--offline", "--max-workers=2", "--console=plain"], "build.log")
    target = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    runner = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    verify(target, "com.theoriacodex.debug")
    verify(runner, "com.theoriacodex.debug.test")
    device = [adb, "-s", args.serial]
    run(device + ["install", "-r", str(target)], "install-target.log")
    run(device + ["install", "-r", str(runner)], "install-runner.log")
    token = uuid.uuid4().hex
    (evidence / "token.txt").write_text(token)
    with fixture_server(evidence) as (port, release):
        run(device + ["reverse", f"tcp:{port}", f"tcp:{port}"], "reverse.log")
        try:
            for phase in ("write", "read"):
                run(device + ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
                    "-e", "theoriaRecoveryPhase", phase, "-e", "theoriaRecoveryToken", token,
                    "-e", "theoriaRecoveryUrl", f"http://127.0.0.1:{port}", RUNNER], f"{phase}.log")
                if "OK (1 test)" not in (evidence / f"{phase}.log").read_text():
                    raise SystemExit(f"Native recovery {phase} failed; inspect its retained log")
                if phase == "write":
                    run(device + ["shell", "am", "force-stop", "com.theoriacodex.debug"], "force-stop.log")
                    release.set()
        finally:
            release.set()
            subprocess.run(device + ["reverse", "--remove", f"tcp:{port}"], check=False)
    print("Native recovery passed in two target processes; completed files and native IDs were preserved.")


if __name__ == "__main__":
    main()
