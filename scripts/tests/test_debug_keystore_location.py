from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "verify_device_apk", Path(__file__).resolve().parents[1] / "verify_device_apk.py",
)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class DebugKeystoreLocationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.home = self.directory / "runner"
        self.home.mkdir()
        self.enterContext(patch.dict(os.environ, {}, clear=True))
        self.enterContext(patch.object(VERIFIER.Path, "home", return_value=self.home))

    def key(self, relative: str) -> Path:
        path = self.directory / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"Certificate content is validated separately by the artifact preflight")
        return path

    def test_hosted_xdg_directory_matches_agp_instead_of_legacy_home_key(self) -> None:
        self.key("runner/.android/debug.keystore")
        expected = self.key("runner/.config/.android/debug.keystore")
        os.environ["XDG_CONFIG_HOME"] = str(self.home / ".config")
        self.assertEqual(expected, VERIFIER.debug_keystore())

    def test_explicit_signing_lane_takes_precedence_over_android_locations(self) -> None:
        expected = self.key("configured/debug.keystore")
        os.environ.update({
            "ANDROID_DEBUG_KEYSTORE": str(expected),
            "ANDROID_USER_HOME": str(self.directory / "different"),
            "XDG_CONFIG_HOME": str(self.home),
        })
        self.assertEqual(expected, VERIFIER.debug_keystore())

    def test_android_user_home_is_direct_but_legacy_roots_receive_android_suffix(self) -> None:
        for variable, relative in (
            ("ANDROID_USER_HOME", "custom/debug.keystore"),
            ("ANDROID_PREFS_ROOT", "custom/.android/debug.keystore"),
            ("ANDROID_SDK_HOME", "custom/.android/debug.keystore"),
        ):
            with self.subTest(variable=variable), patch.dict(os.environ, {variable: str(self.directory / "custom")}, clear=True):
                self.assertEqual(self.key(relative), VERIFIER.debug_keystore())

    def test_missing_selected_key_never_falls_back_to_an_existing_different_key(self) -> None:
        self.key("runner/.android/debug.keystore")
        for variable, location in (
            ("ANDROID_DEBUG_KEYSTORE", self.directory / "missing.keystore"),
            ("ANDROID_USER_HOME", self.directory / "missing"),
            ("XDG_CONFIG_HOME", self.directory / "xdg"),
        ):
            with self.subTest(variable=variable), patch.dict(os.environ, {variable: str(location)}, clear=True):
                if variable == "XDG_CONFIG_HOME":
                    location.mkdir()
                with self.assertRaisesRegex(ValueError, "Debug signing keystore is missing"):
                    VERIFIER.debug_keystore()

    def test_android_location_aliases_must_resolve_to_the_same_directory(self) -> None:
        expected = self.key("shared/.android/debug.keystore")
        os.environ.update({
            "ANDROID_USER_HOME": str(expected.parent),
            "ANDROID_PREFS_ROOT": str(expected.parent.parent),
        })
        self.assertEqual(expected, VERIFIER.debug_keystore())
        os.environ["ANDROID_USER_HOME"] = str(self.directory / "other")
        with self.assertRaisesRegex(ValueError, "Conflicting Android preference directories"):
            VERIFIER.debug_keystore()

    def test_existing_test_root_precedes_xdg_and_unset_overrides_use_home(self) -> None:
        home_key = self.key("runner/.android/debug.keystore")
        self.assertEqual(home_key, VERIFIER.debug_keystore())
        expected = self.key("test/.android/debug.keystore")
        self.key("xdg/.android/debug.keystore")
        os.environ.update({"TEST_TMPDIR": str(self.directory / "test"), "XDG_CONFIG_HOME": str(self.directory / "xdg")})
        self.assertEqual(expected, VERIFIER.debug_keystore())
        os.environ["TEST_TMPDIR"] = str(self.directory / "absent")
        self.assertEqual(self.directory / "xdg/.android/debug.keystore", VERIFIER.debug_keystore())


if __name__ == "__main__":
    unittest.main()
