from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ElementTree

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import copy_production_to_debug as copy

CERTIFICATE = "ab" * 32


def copy_manifest(package: str) -> ElementTree.Element:
    component = (
        f'<activity android:name="{copy.IMPORTER}" android:exported="true" android:process=":developerCopy"/>'
        if package == copy.DEBUG else
        f'<provider android:name="{copy.PROVIDER}" android:exported="true" android:process=":developerCopy" '
        f'android:authorities="{copy.AUTHORITY}" android:grantUriPermissions="false">'
        f'<meta-data android:name="developerCopyDebugCertificateSha256" android:value="{CERTIFICATE}"/></provider>'
    )
    return ElementTree.fromstring(
        f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">'
        f'<application android:debuggable="{str(package == copy.DEBUG).lower()}">{component}</application></manifest>'
    )


class FakeDevice:
    def __init__(self, fail_preflight: bool = False, state: str = "complete"):
        self.calls: list[tuple[str, ...]] = []
        self.request: dict[str, str] = {}
        self.fail_preflight = fail_preflight
        self.state = state

    def require_installed_apk(self, package: str, expected: Path, destination: Path) -> None:
        self.calls.append(("verify-installed", package))
        if self.fail_preflight:
            raise ValueError("Installed APK does not match")

    def run(self, *arguments: str, input_bytes: bytes | None = None) -> bytes:
        self.calls.append(arguments)
        if input_bytes:
            self.request = json.loads(input_bytes)
        if arguments == ("shell", "date", "+%s"):
            return b"1000"
        if arguments == ("shell", "run-as", copy.DEBUG, "cat", copy.STATUS):
            return json.dumps({"job": self.request["job"], "state": self.state}).encode()
        return b""


class CopyProductionToDebugTest(unittest.TestCase):
    def test_exact_components_pins_and_processes_are_required(self) -> None:
        for package in (copy.DEBUG, copy.PRODUCTION):
            copy.validate_manifest(copy_manifest(package), package, CERTIFICATE)
        cases = [
            (copy.PRODUCTION, "provider", "exported", "false"),
            (copy.PRODUCTION, "provider", "process", "com.theoriacodex"),
            (copy.PRODUCTION, "provider", "authorities", "other.authority"),
            (copy.PRODUCTION, "provider", "grantUriPermissions", "true"),
            (copy.DEBUG, "activity", "process", "com.theoriacodex.debug"),
        ]
        for package, tag, attribute, value in cases:
            with self.subTest(attribute=attribute):
                root = copy_manifest(package)
                root.find(f"application/{tag}").set(copy.ANDROID + attribute, value)
                with self.assertRaises(ValueError):
                    copy.validate_manifest(root, package, CERTIFICATE)
        with self.assertRaises(ValueError):
            copy.validate_manifest(copy_manifest(copy.PRODUCTION), copy.PRODUCTION, "cd" * 32)

    def test_public_or_debuggable_production_and_instrumentation_are_rejected(self) -> None:
        root = copy_manifest(copy.PRODUCTION)
        application = root.find("application")
        application.remove(application.find("provider"))
        with self.assertRaises(ValueError):
            copy.validate_manifest(root, copy.PRODUCTION, CERTIFICATE)
        root = copy_manifest(copy.PRODUCTION)
        root.find("application").set(copy.ANDROID + "debuggable", "true")
        with self.assertRaises(ValueError):
            copy.validate_manifest(root, copy.PRODUCTION, CERTIFICATE)
        root = copy_manifest(copy.DEBUG)
        ElementTree.SubElement(root, "instrumentation")
        with self.assertRaises(ValueError):
            copy.validate_manifest(root, copy.DEBUG, CERTIFICATE)

    def test_dry_run_finishes_without_any_device_access(self) -> None:
        with patch.object(sys, "argv", ["copy", "--debug-apk", "debug.apk", "--personal-apk", "personal.apk", "--dry-run"]), \
                patch.object(copy, "verify_apks", return_value=CERTIFICATE), patch.object(copy, "Device") as device, \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, copy.main())
            device.assert_not_called()

    def test_copy_has_no_production_package_mutations_and_passes_only_public_request_data_through_adb(self) -> None:
        device = FakeDevice()
        with contextlib.redirect_stdout(io.StringIO()):
            copy.copy_to_debug(device, Path("debug.apk"), Path("personal.apk"), CERTIFICATE)
        self.assertEqual([("verify-installed", copy.DEBUG), ("verify-installed", copy.PRODUCTION)], device.calls[:2])
        self.assertEqual({"job", "issuedAtEpochMs", "productionCertificateSha256"}, set(device.request))
        self.assertEqual(CERTIFICATE, device.request["productionCertificateSha256"])
        for command in device.calls[2:]:
            self.assertNotIn(copy.PRODUCTION, command)
            self.assertNotIn("install", command)
            self.assertNotIn("uninstall", command)
            self.assertNotIn("clear", command)
        self.assertEqual(("shell", "run-as", copy.DEBUG, "rm", "-f", copy.REQUEST), device.calls[-1])

    def test_installed_mismatch_blocks_all_mutations_and_failed_import_cleans_only_debug(self) -> None:
        device = FakeDevice(fail_preflight=True)
        with self.assertRaises(ValueError):
            copy.copy_to_debug(device, Path("debug.apk"), Path("personal.apk"), CERTIFICATE)
        self.assertEqual([("verify-installed", copy.DEBUG)], device.calls)
        device = FakeDevice(state="failed")
        with self.assertRaises(ValueError):
            copy.copy_to_debug(device, Path("debug.apk"), Path("personal.apk"), CERTIFICATE)
        self.assertIn(("shell", "am", "force-stop", copy.DEBUG), device.calls[-2:])

    def test_actual_installed_apk_must_match_every_byte(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            expected = Path(directory) / "expected.apk"
            expected.write_bytes(b"verified APK")
            installed = Path(directory) / "installed.apk"
            def run(*arguments: str, **_: object) -> bytes:
                if arguments[0] == "pull":
                    installed.write_bytes(b"different build, same possible signing key")
                    return b""
                return b"package:/data/app/base.apk\n"
            with patch.object(copy.Device, "run", side_effect=run):
                device = object.__new__(copy.Device)
                with self.assertRaises(ValueError):
                    device.require_installed_apk(copy.PRODUCTION, expected, installed)

    def test_multiple_and_unverified_signers_fail(self) -> None:
        with patch.object(copy, "run", return_value=b"Signer #1 certificate SHA-256 digest: " + CERTIFICATE.encode()):
            self.assertEqual(CERTIFICATE, copy.certificate_digest(Path("apk"), "signer"))
        with patch.object(copy, "run", return_value=(
            f"Signer #1 certificate SHA-256 digest: {CERTIFICATE}\nSigner #2 certificate SHA-256 digest: {'cd' * 32}".encode()
        )):
            with self.assertRaises(ValueError):
                copy.certificate_digest(Path("apk"), "signer")
        with patch.object(copy, "run", return_value=b"No verified signatures"):
            with self.assertRaises(ValueError):
                copy.certificate_digest(Path("apk"), "signer")


if __name__ == "__main__":
    unittest.main()
