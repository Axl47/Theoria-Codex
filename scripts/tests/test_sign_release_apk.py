import base64
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("sign_release_apk", Path(__file__).resolve().parents[1] / "sign_release_apk.py")
signer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(signer)


class SignReleaseApkTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "unsigned.apk"
        self.source.write_bytes(b"unsigned APK fixture")
        self.destination = self.root / "release.apk"
        self.runner_temp = self.root / "runner"
        self.runner_temp.mkdir()
        tool = self.root / "sdk/build-tools/36.0.0/apksigner"
        tool.parent.mkdir(parents=True)
        tool.touch()
        self.environment = {
            "ANDROID_HOME": str(self.root / "sdk"),
            "RUNNER_TEMP": str(self.runner_temp),
            "GITHUB_OUTPUT": str(self.root / "output"),
            "THEORIA_UPLOAD_KEYSTORE_BASE64": base64.b64encode(b"keystore fixture").decode(),
            "THEORIA_UPLOAD_KEY_ALIAS": "fixture",
            "THEORIA_UPLOAD_STORE_PASSWORD": "fixture-store-password",
            "THEORIA_UPLOAD_KEY_PASSWORD": "fixture-key-password",
        }

    def invoke(self, fail_verification=False):
        def run(command, *, check):
            self.assertTrue(check)
            self.assertNotIn("fixture-store-password", command)
            self.assertNotIn("fixture-key-password", command)
            if command[1] == "sign":
                key = Path(command[command.index("--ks") + 1])
                self.assertEqual(b"keystore fixture", key.read_bytes())
                self.assertEqual(0o600, key.stat().st_mode & 0o777)
                Path(command[command.index("--out") + 1]).write_bytes(b"signed APK fixture")
            else:
                self.assertEqual("verify", command[1])
                self.assertFalse(self.destination.exists())
                if fail_verification:
                    raise subprocess.CalledProcessError(1, command)
        with patch.dict(os.environ, self.environment, clear=True), patch.object(signer.subprocess, "run", side_effect=run):
            signer.sign_apk(self.source, self.destination)

    def test_verified_artifact_is_published_without_retaining_the_key(self):
        self.invoke()
        self.assertEqual(b"signed APK fixture", self.destination.read_bytes())
        self.assertEqual(b"unsigned APK fixture", self.source.read_bytes())
        self.assertEqual([], list(self.runner_temp.iterdir()))
        self.assertEqual(f"signedReleaseFile={self.destination.resolve()}\n", (self.root / "output").read_text())

    def test_failed_verification_leaves_no_artifact_output_or_key(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.invoke(fail_verification=True)
        self.assertFalse(self.destination.exists())
        self.assertFalse((self.root / "output").exists())
        self.assertEqual([], list(self.runner_temp.iterdir()))

    def test_existing_output_is_never_overwritten(self):
        self.destination.write_bytes(b"existing APK")
        with self.assertRaises(ValueError):
            self.invoke()
        self.assertEqual(b"existing APK", self.destination.read_bytes())
        self.assertEqual([], list(self.runner_temp.iterdir()))


if __name__ == "__main__":
    unittest.main()
