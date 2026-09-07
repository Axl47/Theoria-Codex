from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/verify_release_acceptance_device.sh"
VERIFIER = ROOT / "scripts/verify_device_apk.py"
CERTIFICATE = b"test-only certificate bytes"


class ReleaseAcceptancePreflightTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.apk = self.directory / "candidate with spaces.apk"
        self.apk.write_bytes(b"Test inputs are inspected exclusively by the fake SDK tools")
        self.keystore = self.directory / "debug.keystore"
        self.keystore.write_bytes(CERTIFICATE)
        self.adb_log = self.directory / "adb.jsonl"
        self.environment = dict(os.environ)
        self.environment.update({
            "ANDROID_DEBUG_KEYSTORE": str(self.keystore),
            "PREFLIGHT_ADB_LOG": str(self.adb_log),
            "PREFLIGHT_METADATA": json.dumps({
                "package": "com.theoriacodex.acceptance", "debuggable": "false",
                "version": "0.10.0-acceptance", "signature": hashlib.sha256(CERTIFICATE).hexdigest(),
            }),
        })
        program = textwrap.dedent('''\
            #!/usr/bin/env python3
            import json, os, pathlib, sys
            kind = pathlib.Path(sys.argv[0]).name
            values = json.loads(os.environ["PREFLIGHT_METADATA"])
            if kind == "adb":
                with open(os.environ["PREFLIGHT_ADB_LOG"], "a") as output:
                    output.write(json.dumps(sys.argv[1:]) + "\\n")
                sys.exit(42)
            if values.get("fail_tool") == kind:
                sys.exit(1)
            if kind == "apkanalyzer":
                if sys.argv[2] == "print":
                    extra = '<meta-data android:name="listener" android:value="SideEffectRunListener"/>' if values.get("listener") else ''
                    runner = '<instrumentation android:name="androidx.test.runner.AndroidJUnitRunner" android:targetPackage="com.theoriacodex"/>' if values.get("production_target") else ''
                    print('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>' + extra + '</application>' + runner + '</manifest>')
                else:
                    field = {"application-id": "package", "debuggable": "debuggable", "version-name": "version"}[sys.argv[2]]
                    print(values[field])
            elif kind == "apksigner":
                prefix = "V2 Signer:" if values.get("versioned_signer") else "Signer #1"
                print(prefix + " certificate SHA-256 digest: " + values["signature"])
            elif kind == "keytool":
                sys.stdout.buffer.write(b"test-only certificate bytes")
        ''')
        for name in ["adb", "apkanalyzer", "apksigner", "keytool"]:
            path = self.directory / name
            path.write_text(program)
            path.chmod(0o755)
            self.environment[name.upper()] = str(path)

    def invoke(self, **changes: str) -> subprocess.CompletedProcess[str]:
        values = json.loads(self.environment["PREFLIGHT_METADATA"])
        values.update(changes)
        env = {**self.environment, "PREFLIGHT_METADATA": json.dumps(values)}
        return subprocess.run(["bash", str(SCRIPT), str(self.apk)], env=env, capture_output=True, text=True)

    def test_wrong_package_signer_or_lane_never_reaches_adb(self) -> None:
        cases = [
            {"package": "com.theoriacodex"}, {"package": "com.theoriacodex.debug"},
            {"debuggable": "true"}, {"debuggable": "unknown"}, {"version": "0.10.0"},
            {"signature": "ab" * 32}, {"signature": ""},
            {"fail_tool": "apkanalyzer"}, {"fail_tool": "apksigner"}, {"fail_tool": "keytool"},
            {"listener": "true"}, {"production_target": "true"},
        ]
        for case in cases:
            with self.subTest(case=case):
                result = self.invoke(**case)
                self.assertNotEqual(0, result.returncode)
                self.assertNotEqual(42, result.returncode)
                self.assertFalse(self.adb_log.exists(), result.stdout + result.stderr)

    def test_missing_apk_never_reaches_adb(self) -> None:
        self.apk.unlink()
        self.assertNotEqual(0, self.invoke().returncode)
        self.assertFalse(self.adb_log.exists())

    def test_verified_candidate_reaches_only_the_fake_device_boundary(self) -> None:
        result = self.invoke()
        self.assertEqual(42, result.returncode, result.stdout + result.stderr)
        self.assertIn("Verified before device access", result.stdout)
        commands = [json.loads(line) for line in self.adb_log.read_text().splitlines()]
        self.assertEqual([["wait-for-device"]], commands)

    def test_versioned_apksigner_output_still_requires_the_matching_certificate(self) -> None:
        result = self.invoke(versioned_signer="true", signature="ab" * 32)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.adb_log.exists())
        result = self.invoke(versioned_signer="true")
        self.assertEqual(42, result.returncode, result.stdout + result.stderr)

    def test_verifier_can_be_used_as_a_host_only_artifact_check(self) -> None:
        result = subprocess.run(
            ["python3", str(VERIFIER), str(self.apk)], env=self.environment, capture_output=True, text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(self.adb_log.exists())


if __name__ == "__main__":
    unittest.main()
