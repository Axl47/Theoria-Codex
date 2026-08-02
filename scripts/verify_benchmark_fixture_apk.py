#!/usr/bin/env python3
"""Fail closed unless the final benchmark APK contains its isolated offline fixture."""

from __future__ import annotations

import hashlib
import subprocess
import sys
import zipfile
from pathlib import Path


EXPECTED_APPLICATION_ID = "com.theoriacodex.benchmark"
EXPECTED_ACTIVITY = "com.theoriacodex.app.benchmark.BenchmarkFixtureActivity"
EXPECTED_ACTION = "com.theoriacodex.action.BENCHMARK_FIXTURE"
EXPECTED_PROCESS = ":benchmarkFixture"
EXPECTED_LAUNCHER = "com.theoriacodex.app.MainActivity"
FORBIDDEN_MANIFEST_SURFACES = (
    "android.permission.INTERNET",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.intent.action.VIEW",
    "android.intent.category.BROWSABLE",
    "androidx.core.content.FileProvider",
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


def sha256(contents: bytes) -> str:
    return hashlib.sha256(contents).hexdigest()


def main() -> None:
    require(len(sys.argv) == 4, "usage: verify_benchmark_fixture_apk.py APK VIDEO APKANALYZER")
    apk = Path(sys.argv[1])
    source_video = Path(sys.argv[2])
    analyzer = Path(sys.argv[3])
    require(apk.is_file(), f"benchmark APK is missing: {apk}")
    require(source_video.is_file(), f"benchmark fixture video is missing: {source_video}")
    require(analyzer.is_file(), f"apkanalyzer is missing: {analyzer}")

    application_id = run(analyzer, "manifest", "application-id", str(apk))
    require(application_id == EXPECTED_APPLICATION_ID, f"unexpected application ID: {application_id}")

    manifest = run(analyzer, "manifest", "print", str(apk))
    for expected in (EXPECTED_ACTIVITY, EXPECTED_ACTION, EXPECTED_PROCESS):
        require(expected in manifest, f"benchmark manifest is missing {expected}")
    for forbidden in FORBIDDEN_MANIFEST_SURFACES:
        require(forbidden not in manifest, f"benchmark manifest retains forbidden surface {forbidden}")
    for launcher_contract in (
        EXPECTED_LAUNCHER,
        "android.intent.action.MAIN",
        "android.intent.category.LAUNCHER",
    ):
        require(launcher_contract in manifest, f"benchmark manifest is missing {launcher_contract}")
    activity_offset = manifest.index(EXPECTED_ACTIVITY)
    activity_block = manifest[activity_offset : activity_offset + 800]
    require('android:exported="true"' in activity_block, "benchmark fixture is not exported")

    dex_packages = run(analyzer, "dex", "packages", str(apk))
    require(EXPECTED_ACTIVITY in dex_packages, "benchmark fixture activity was removed from dex")

    packaged_video_path = run(
        analyzer,
        "resources",
        "value",
        "--config",
        "default",
        "--type",
        "raw",
        "--name",
        "benchmark_loop",
        str(apk),
    )
    require(packaged_video_path.startswith("res/"), "raw/benchmark_loop has no packaged resource path")
    require(packaged_video_path.endswith(".mp4"), "raw/benchmark_loop is not packaged as MP4")
    with zipfile.ZipFile(apk) as archive:
        packaged_video = archive.read(packaged_video_path)
    source_bytes = source_video.read_bytes()
    require(packaged_video == source_bytes, "packaged benchmark video bytes differ from the source fixture")

    print(
        "Verified benchmark fixture: "
        f"{EXPECTED_APPLICATION_ID}, {EXPECTED_PROCESS}, {packaged_video_path}, "
        f"{len(packaged_video)} bytes, sha256={sha256(packaged_video)}",
    )


if __name__ == "__main__":
    main()
