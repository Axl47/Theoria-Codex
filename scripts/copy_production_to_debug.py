#!/usr/bin/env python3
"""Copy a read-only personal-release snapshot directly into Debug on one connected device.

Never installs, clears, stops, or launches the production app's ordinary process. Credentials
never pass through ADB, host files, stdout, or stderr. --dry-run makes no ADB calls.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ElementTree

import verify_device_apk


PRODUCTION = "com.theoriacodex"
DEBUG = "com.theoriacodex.debug"
PROVIDER = "com.theoriacodex.app.developercopy.DeveloperCopyProvider"
IMPORTER = "com.theoriacodex.app.developercopy.DeveloperCopyActivity"
AUTHORITY = PRODUCTION + ".developer-copy"
ANDROID = "{http://schemas.android.com/apk/res/android}"
REQUEST = "no_backup/developer_copy/request.json"
STATUS = "no_backup/developer_copy/status.json"


def run(*command: str, input_bytes: bytes | None = None) -> bytes:
    return subprocess.run(command, input=input_bytes, check=True, capture_output=True, timeout=45).stdout


def certificate_digest(apk: Path, signer: str) -> str:
    output = run(signer, "verify", "--print-certs", str(apk)).decode()
    digests = {
        digest.replace(":", "").lower()
        for digest in re.findall(
            r"(?:Signer #\d+|V\d+(?:\.\d+)? Signer):? certificate SHA-256 digest:\s*([0-9a-fA-F:]+)",
            output,
        )
    }
    if len(digests) != 1 or not re.fullmatch(r"[0-9a-f]{64}", next(iter(digests))):
        raise ValueError("APK must have exactly one verified signing certificate")
    return next(iter(digests))


def manifest(apk: Path, analyzer: str) -> ElementTree.Element:
    return ElementTree.fromstring(run(analyzer, "manifest", "print", str(apk)))


def validate_manifest(root: ElementTree.Element, package: str, debug_certificate: str) -> None:
    application = root.find("application")
    if root.get("package") != package or application is None or root.findall("instrumentation"):
        raise ValueError("Wrong application or unexpected instrumentation in copy APK")
    if application.get(ANDROID + "debuggable", "false") != ("true" if package == DEBUG else "false"):
        raise ValueError("Wrong debuggability for copy lane")
    component_type, component_name = ("activity", IMPORTER) if package == DEBUG else ("provider", PROVIDER)
    components = [node for node in application.findall(component_type) if node.get(ANDROID + "name") == component_name]
    if len(components) != 1:
        raise ValueError("Copy component is absent; public production APKs cannot export")
    component = components[0]
    if component.get(ANDROID + "exported") != "true" or component.get(ANDROID + "process") != ":developerCopy":
        raise ValueError("Copy component must use the isolated developer process")
    if package == PRODUCTION:
        if component.get(ANDROID + "authorities") != AUTHORITY or component.get(ANDROID + "grantUriPermissions") != "false":
            raise ValueError("Unexpected production copy provider configuration")
        pins = [node.get(ANDROID + "value") for node in component.findall("meta-data")
                if node.get(ANDROID + "name") == "developerCopyDebugCertificateSha256"]
        if pins != [debug_certificate]:
            raise ValueError("Personal release is not pinned to this Debug signing certificate")
    elif any(node.get(ANDROID + "name") == PROVIDER for node in application.findall("provider")):
        raise ValueError("Debug must not expose a production export provider")


def verify_apks(debug_apk: Path, personal_apk: Path) -> str:
    verify_device_apk.verify(debug_apk, DEBUG)
    analyzer, signer, _, _ = verify_device_apk.tools()
    debug_certificate = certificate_digest(debug_apk, signer)
    production_certificate = certificate_digest(personal_apk, signer)
    if production_certificate == debug_certificate:
        raise ValueError("Personal release must use the production signing key, not the Debug key")
    validate_manifest(manifest(debug_apk, analyzer), DEBUG, debug_certificate)
    validate_manifest(manifest(personal_apk, analyzer), PRODUCTION, debug_certificate)
    return production_certificate


class Device:
    def __init__(self, serial: str):
        self.prefix = (str(verify_device_apk.sdk_directory() / "platform-tools/adb"), "-s", serial)

    def run(self, *arguments: str, input_bytes: bytes | None = None) -> bytes:
        return run(*self.prefix, *arguments, input_bytes=input_bytes)

    def require_installed_apk(self, package: str, expected: Path, destination: Path) -> None:
        paths = self.run("shell", "pm", "path", package).decode().strip().splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:/"):
            raise ValueError(f"Expected one installed APK for {package}")
        self.run("pull", paths[0].removeprefix("package:"), str(destination))
        if file_digest(destination) != file_digest(expected):
            raise ValueError(f"Installed {package} differs from the verified APK; install the intended signed build first")


def file_digest(path: Path) -> bytes:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.digest()


def copy_to_debug(device: Device, debug_apk: Path, personal_apk: Path, certificate: str) -> None:
    # Read-only package checks precede every Debug mutation. The exact APK comparison also
    # verifies installed component configuration and certificates without dumpsys parsing.
    with tempfile.TemporaryDirectory(prefix="theoria-copy-apk-") as directory:
        device.require_installed_apk(DEBUG, debug_apk, Path(directory) / "debug.apk")
        device.require_installed_apk(PRODUCTION, personal_apk, Path(directory) / "personal.apk")
    device.run("shell", "run-as", DEBUG, "id")
    device.run("shell", "am", "force-stop", DEBUG)
    job = str(uuid.uuid4())
    issued_at = int(device.run("shell", "date", "+%s").decode().strip()) * 1000
    request = json.dumps({"job": job, "issuedAtEpochMs": issued_at,
                          "productionCertificateSha256": certificate}).encode()
    try:
        device.run("shell", "run-as", DEBUG, "sh", "-c",
                   f"'mkdir -p no_backup/developer_copy && cat > {REQUEST}'", input_bytes=request)
        device.run("shell", "am", "start", "-W", "-n", f"{DEBUG}/{IMPORTER}", "--es", "job", job)
        await_result(device, job)
    finally:
        device.run("shell", "am", "force-stop", DEBUG)
        device.run("shell", "run-as", DEBUG, "rm", "-f", REQUEST)
    print("Copy complete. Open Theoria Debug to use the copied profile and accounts.")


def await_result(device: Device, job: str) -> None:
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        try:
            status = json.loads(device.run("shell", "run-as", DEBUG, "cat", STATUS))
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            status = {}
        if status.get("job") == job:
            if status.get("state") == "complete":
                return
            if status.get("state") == "failed":
                raise ValueError("Copy failed. Check personal-build setup and finish pending production recovery before retrying")
        time.sleep(1)
    raise ValueError("Copy timed out; retry after checking the build setup. Any admitted Debug restore remains recoverable")


def print_debug_certificate() -> None:
    _, _, keytool, keystore = verify_device_apk.tools()
    certificate = run(keytool, "-exportcert", "-keystore", str(keystore), "-alias", "androiddebugkey", "-storepass", "android")
    if not certificate:
        raise ValueError("Debug certificate is empty")
    print(hashlib.sha256(certificate).hexdigest())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--debug-apk", type=Path)
    parser.add_argument("--personal-apk", type=Path)
    parser.add_argument("--dry-run", action="store_true", help="Verify APKs on the host; make no ADB calls")
    parser.add_argument("--print-debug-certificate", action="store_true", help="Print the public Debug certificate digest only")
    arguments = parser.parse_args()
    try:
        if arguments.print_debug_certificate:
            print_debug_certificate()
            return 0
        if arguments.debug_apk is None or arguments.personal_apk is None:
            parser.error("--debug-apk and --personal-apk are required")
        certificate = verify_apks(arguments.debug_apk, arguments.personal_apk)
        if arguments.dry_run:
            print("Host preflight passed. No device access. Production will only export; Debug will receive an additive copy.")
            return 0
        if not arguments.serial:
            parser.error("--serial is required for connected-device work")
        print("Reading the personal production build; stopping and importing into com.theoriacodex.debug only.")
        copy_to_debug(Device(arguments.serial), arguments.debug_apk, arguments.personal_apk, certificate)
        return 0
    except ValueError as failure:
        print(str(failure), file=sys.stderr)
        return 1
    except (OSError, subprocess.SubprocessError, ElementTree.ParseError):
        # Never replay subprocess stderr or app exception text into the console.
        print("Copy command failed; verify APK paths, Android tools, and the selected device. No credential output was collected.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
