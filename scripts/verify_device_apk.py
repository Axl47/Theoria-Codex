#!/usr/bin/env python3
"""Prove a packaged isolated application ID and debug signing certificate before ADB."""

from __future__ import annotations

import hashlib
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ElementTree


EXPECTED_PACKAGE = "com.theoriacodex.acceptance"
# Expected debuggability is part of the lane, not a caller-supplied escape hatch.
ISOLATED_LANES = {
    EXPECTED_PACKAGE: "false",
    "com.theoriacodex.debug": "true",
    "com.theoriacodex.debug.test": "true",
    "com.theoriacodex.data.android.test": "true",
    "com.theoriacodex.benchmark": "false",
    "com.theoriacodex.macrobenchmark": "true",
    "com.theoriacodex.baselineprofile": "false",
    "com.theoriacodex.baselineprofile.test": "true",
}
INSTRUMENTATION_TARGETS = {
    "com.theoriacodex.debug.test": "com.theoriacodex.debug",
    "com.theoriacodex.data.android.test": "com.theoriacodex.data.android.test",
    "com.theoriacodex.macrobenchmark": "com.theoriacodex.macrobenchmark",
    "com.theoriacodex.baselineprofile.test": "com.theoriacodex.baselineprofile.test",
}


def sdk_directory() -> Path:
    configured = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if configured:
        return Path(configured)
    local = Path(__file__).resolve().parents[1] / "local.properties"
    if local.is_file():
        for line in local.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.partition("=")[2].replace("\\:", ":").replace("\\ ", " "))
    raise ValueError("Set ANDROID_HOME or ANDROID_SDK_ROOT to inspect the packaged APK")


def tools() -> tuple[str, str, str, Path]:
    analyzer = os.environ.get("APKANALYZER")
    signer = os.environ.get("APKSIGNER")
    if not analyzer or not signer:
        sdk = sdk_directory()
        # Use the project's SDK, not an unrelated command-line-tools installation on PATH.
        analyzer = analyzer or str(sdk / "cmdline-tools/latest/bin/apkanalyzer")
        versions = sorted(
            (sdk / "build-tools").glob("*/apksigner"),
            key=lambda path: tuple(int(part) for part in re.findall(r"\d+", path.parent.name)),
        )
        if not signer and not versions:
            raise ValueError("Android build tools contain no apksigner")
        signer = signer or str(versions[-1])
    keytool = os.environ.get("KEYTOOL") or shutil.which("keytool")
    if not keytool:
        raise ValueError("keytool is required to verify the debug signing certificate")
    keystore = Path(os.environ.get("ANDROID_DEBUG_KEYSTORE", str(Path.home() / ".android/debug.keystore")))
    if not keystore.is_file():
        raise ValueError(f"Debug signing keystore is missing: {keystore}")
    return analyzer, signer, keytool, keystore


def run(*command: str) -> bytes:
    return subprocess.run(command, check=True, capture_output=True).stdout


def verify(apk: Path, expected_package: str = EXPECTED_PACKAGE) -> None:
    if not apk.is_file():
        raise ValueError(f"APK is missing: {apk}")
    if expected_package not in ISOLATED_LANES:
        raise ValueError("Only explicitly isolated device-test packages are allowed")
    analyzer, signer, keytool, keystore = tools()
    package = run(analyzer, "manifest", "application-id", str(apk)).decode().strip()
    if package != expected_package:
        raise ValueError(f"Expected {expected_package}; packaged application ID is {package!r}")
    debuggable = run(analyzer, "manifest", "debuggable", str(apk)).decode().strip()
    if debuggable != ISOLATED_LANES[expected_package]:
        raise ValueError(f"Wrong debuggability for {package}: {debuggable!r}")
    version = run(analyzer, "manifest", "version-name", str(apk)).decode().strip()
    if expected_package == EXPECTED_PACKAGE and not version.endswith("-acceptance"):
        raise ValueError(f"Acceptance APK has the wrong version lane: {version!r}")
    raw_manifest = run(analyzer, "manifest", "print", str(apk)).decode()
    manifest = ElementTree.fromstring(raw_manifest)
    android = "{http://schemas.android.com/apk/res/android}"
    runners = manifest.findall("instrumentation")
    target = INSTRUMENTATION_TARGETS.get(expected_package)
    if target:
        if len(runners) != 1 or runners[0].get(android + "targetPackage") != target:
            raise ValueError(f"Unexpected instrumentation target for {package}")
        if runners[0].get(android + "name") != "androidx.test.runner.AndroidJUnitRunner":
            raise ValueError("Unexpected instrumentation runner")
    elif runners:
        raise ValueError("Application APK unexpectedly declares an instrumentation runner")
    if "SideEffectRunListener" in raw_manifest or any(
        node.get(android + "name") == "listener" for node in manifest.iter("meta-data")
    ):
        raise ValueError("Package-mutating instrumentation listeners are not allowed")
    # Verify the APK's cryptographic signature, not merely its certificate subject name.
    signatures = run(signer, "verify", "--print-certs", str(apk)).decode()
    digests = {
        value.replace(":", "").lower()
        for value in re.findall(
            r"(?:Signer #\d+|V\d+(?:\.\d+)? Signer):? certificate SHA-256 digest:\s*([0-9a-fA-F:]+)",
            signatures,
        )
    }
    debug_certificate = run(
        keytool, "-exportcert", "-keystore", str(keystore),
        "-alias", "androiddebugkey", "-storepass", "android",
    )
    if not debug_certificate or digests != {hashlib.sha256(debug_certificate).hexdigest()}:
        raise ValueError("APK is not signed solely by the configured debug certificate")
    print(f"Verified before device access: {package}, {version}, debuggable={debuggable}, matching debug certificate.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--package", choices=sorted(ISOLATED_LANES), default=EXPECTED_PACKAGE)
    args = parser.parse_args()
    try:
        verify(args.apk, args.package)
    except (OSError, ValueError, ElementTree.ParseError, subprocess.CalledProcessError) as error:
        print(f"Device APK preflight failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
