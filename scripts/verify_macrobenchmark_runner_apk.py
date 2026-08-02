#!/usr/bin/env python3
"""Verify that the packaged Macrobenchmark runner does not configure side effects."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


EXPECTED_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
FORBIDDEN_MANIFEST_CONFIGURATION = (
    "SideEffectRunListener",
    'android:name="listener"',
)


def run(analyzer: Path, *arguments: str) -> str:
    completed = subprocess.run(
        [str(analyzer), *arguments],
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout.strip()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def main() -> None:
    require(len(sys.argv) == 3, "usage: verify_macrobenchmark_runner_apk.py APK APKANALYZER")
    apk = Path(sys.argv[1])
    analyzer = Path(sys.argv[2])
    require(apk.is_file(), f"Macrobenchmark runner APK is missing: {apk}")
    require(analyzer.is_file(), f"apkanalyzer is missing: {analyzer}")

    manifest = run(analyzer, "manifest", "print", str(apk))
    require("<instrumentation" in manifest, "Macrobenchmark APK has no instrumentation declaration")
    require(EXPECTED_RUNNER in manifest, f"Macrobenchmark APK does not use {EXPECTED_RUNNER}")
    for forbidden in FORBIDDEN_MANIFEST_CONFIGURATION:
        require(forbidden not in manifest, f"Macrobenchmark manifest configures {forbidden}")

    print(
        "Verified Macrobenchmark runner: AndroidJUnitRunner has no listener configuration; "
        "transitive SideEffectRunListener bytecode, when packaged, remains inert.",
    )


if __name__ == "__main__":
    main()
