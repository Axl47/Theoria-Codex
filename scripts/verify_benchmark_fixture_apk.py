#!/usr/bin/env python3
"""Fail closed unless the final benchmark APK contains its isolated offline fixture."""

from __future__ import annotations

import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


EXPECTED_APPLICATION_ID = "com.theoriacodex.benchmark"
EXPECTED_ACTIVITY = "com.theoriacodex.app.benchmark.BenchmarkFixtureActivity"
EXPECTED_ACTION = "com.theoriacodex.action.BENCHMARK_FIXTURE"
EXPECTED_DURATION_RECEIVER = "com.theoriacodex.app.benchmark.BenchmarkDurationStartReceiver"
EXPECTED_DURATION_ACTION = "com.theoriacodex.action.BENCHMARK_DURATION_START"
EXPECTED_PROCESS = ":benchmarkFixture"
EXPECTED_LAUNCHER = "com.theoriacodex.app.MainActivity"
ANDROID = "{http://schemas.android.com/apk/res/android}"
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


def verify_manifest(manifest: str) -> None:
    for forbidden in FORBIDDEN_MANIFEST_SURFACES:
        require(forbidden not in manifest, f"benchmark manifest retains forbidden surface {forbidden}")
    try:
        root = ET.fromstring(manifest)
    except ET.ParseError as error:
        raise SystemExit(f"Malformed benchmark manifest: {error}") from error
    require(root.tag == "manifest", "benchmark manifest has an unexpected root")
    applications = root.findall("application")
    require(len(applications) == 1, "benchmark manifest must contain exactly one application")

    def component(kind: str, name: str) -> ET.Element:
        matches = [node for node in applications[0].findall(kind) if node.get(ANDROID + "name") == name]
        require(len(matches) == 1, f"benchmark manifest must contain exactly one {kind} {name}")
        return matches[0]

    # Check attributes and actions on their owner, never on an adjacent manifest component.
    for kind, name, action in (
        ("activity", EXPECTED_ACTIVITY, EXPECTED_ACTION),
        ("receiver", EXPECTED_DURATION_RECEIVER, EXPECTED_DURATION_ACTION),
    ):
        node = component(kind, name)
        require(node.get(ANDROID + "exported") == "true", f"{name} is not exported")
        require(node.get(ANDROID + "process") == EXPECTED_PROCESS, f"{name} is outside fixture process")
        require(any(item.get(ANDROID + "name") == action for item in node.findall("intent-filter/action")),
                f"{name} is missing action {action}")

    launcher = component("activity", EXPECTED_LAUNCHER)
    require(any(
        any(item.get(ANDROID + "name") == "android.intent.action.MAIN" for item in intent_filter.findall("action"))
        and any(item.get(ANDROID + "name") == "android.intent.category.LAUNCHER" for item in intent_filter.findall("category"))
        for intent_filter in launcher.findall("intent-filter")
    ), "benchmark MainActivity is missing its launcher filter")


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
    verify_manifest(manifest)

    dex_packages = run(analyzer, "dex", "packages", str(apk))
    require(EXPECTED_ACTIVITY in dex_packages, "benchmark fixture activity was removed from dex")
    require(EXPECTED_DURATION_RECEIVER in dex_packages, "duration signal receiver was removed from dex")

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
