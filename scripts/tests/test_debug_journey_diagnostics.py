"""Run the real debug wrapper against fake tools; no SDK, APK or device is used."""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class DebugJourneyDiagnosticsTest(unittest.TestCase):
    def run_wrapper(self, repo: pathlib.Path, owner: str, test_status: int = 0, **environment: str):
        """Runs verify_debug_device.sh in a fake repository; Gradle and adb calls are logged."""
        scripts = repo / "scripts"
        scripts.mkdir()
        for name in ("verify_debug_device.sh", "device_validation_common.sh"):
            shutil.copyfile(ROOT / "scripts" / name, scripts / name)
        (scripts / "verify_device_apk.py").write_text("# Stub packaged-ID verification in this host-only wrapper test.\n")
        gradle = repo / "gradlew"
        gradle.write_text('''#!/bin/bash
printf '%s\\n' "$*" >> "$FAKE_GRADLE_LOG"
case "$*" in
  *--dry-run*|*assemble*) exit 0 ;;
  *) if [[ -e "$FAKE_CLOSED" ]]; then echo dialogs-closed-before-tests >> "$FAKE_GRADLE_LOG"; fi
     exit "$FAKE_TEST_STATUS" ;;
esac
''')
        adb = repo / "adb"
        adb.write_text('''#!/bin/bash
if [[ "$1" == devices ]]; then
  printf 'List of devices attached\\nemulator-fixture\\tdevice\\n'
else
  printf '%s\\n' "$*" >> "$FAKE_ADB_LOG"
  case "${*:3}" in
    "shell getprop ro.kernel.qemu") printf '%s\\n' "${FAKE_QEMU:-}"; exit 0 ;;
    "shell settings put global hide_error_dialogs 1") exit 0 ;;
    "shell dumpsys window windows")
      if [[ "${FAKE_DIALOG:-}" == stuck || ( "${FAKE_DIALOG:-}" == open && ! -e "$FAKE_CLOSED" ) ]]; then
        echo '  Window #3 Window{1a2b u0 Application Not Responding: com.android.systemui}:'
      else
        echo '  Window #0 Window{3c4d u0 StatusBar}:'
      fi
      exit 0 ;;
    "shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS") touch "$FAKE_CLOSED"; exit 0 ;;
  esac
  exit 1 # Missing diagnostics must not replace the instrumentation result.
fi
''')
        gradle.chmod(0o755)
        adb.chmod(0o755)
        env = os.environ | {"ADB": str(adb), "ANDROID_SERIAL": "emulator-fixture",
                            "FAKE_TEST_STATUS": str(test_status), "FAKE_ADB_LOG": str(repo / "adb.log"),
                            "FAKE_GRADLE_LOG": str(repo / "gradle.log"),
                            "FAKE_CLOSED": str(repo / "dialogs-closed")} | environment
        return subprocess.run(["bash", str(scripts / "verify_debug_device.sh"), owner],
                              env=env, capture_output=True, text=True)

    def test_collects_after_success_and_failure_without_replacing_test_exit_status(self):
        for result in (0, 7):
            with self.subTest(result=result), tempfile.TemporaryDirectory() as temporary:
                repo = pathlib.Path(temporary)
                completed = self.run_wrapper(repo, "app", test_status=result)
                self.assertEqual(result, completed.returncode, completed.stderr)
                self.assertIn("-s emulator-fixture pull /sdcard/Android/data/com.theoriacodex.debug/files/journey-diagnostics",
                              (repo / "adb.log").read_text())

    def test_shard_runs_one_slice_of_every_owner(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = pathlib.Path(temporary)
            completed = self.run_wrapper(repo, "all", DEVICE_TEST_SHARD="2/2")
            self.assertEqual(0, completed.returncode, completed.stderr)
            connected = [line for line in (repo / "gradle.log").read_text().splitlines() if "connected" in line]
            self.assertEqual(2, len(connected))  # The dry-run task graph and the real run match.
            for line in connected:
                self.assertIn(":app:connectedDebugAndroidTest :core-data-android:connectedDebugAndroidTest :media-kit:connectedDebugAndroidTest", line)
                self.assertIn("-Pandroid.testInstrumentationRunnerArguments.numShards=2", line)
                self.assertIn("-Pandroid.testInstrumentationRunnerArguments.shardIndex=1", line)

    def test_emulator_error_dialog_is_hidden_and_closed_before_tests(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = pathlib.Path(temporary)
            completed = self.run_wrapper(repo, "app", FAKE_QEMU="1", FAKE_DIALOG="open")
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertIn("-s emulator-fixture shell settings put global hide_error_dialogs 1", (repo / "adb.log").read_text())
            self.assertIn("dialogs-closed-before-tests", (repo / "gradle.log").read_text())
            closed = next(repo.glob("build/reports/device-preflight/*/closed-error-dialogs.txt")).read_text()
            self.assertIn("Application Not Responding: com.android.systemui", closed)

    def test_emulator_dialog_that_stays_open_stops_before_tests(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = pathlib.Path(temporary)
            completed = self.run_wrapper(repo, "app", FAKE_QEMU="1", FAKE_DIALOG="stuck")
            self.assertEqual(2, completed.returncode, completed.stderr)
            self.assertIn("restart the emulator", completed.stderr)
            connected = [line for line in (repo / "gradle.log").read_text().splitlines()
                         if "connected" in line and "--dry-run" not in line]
            self.assertEqual([], connected)

    def test_ci_preparation_is_not_repeated(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = pathlib.Path(temporary)
            completed = self.run_wrapper(repo, "app", FAKE_QEMU="1", FAKE_DIALOG="stuck", EMULATOR_DIALOGS_PREPARED="1")
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertNotIn("settings put", (repo / "adb.log").read_text())

    def test_physical_device_settings_are_left_untouched(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = pathlib.Path(temporary)
            completed = self.run_wrapper(repo, "app", FAKE_QEMU="0", FAKE_DIALOG="open")
            self.assertEqual(0, completed.returncode, completed.stderr)
            adb_log = (repo / "adb.log").read_text()
            self.assertNotIn("settings put", adb_log)
            self.assertNotIn("CLOSE_SYSTEM_DIALOGS", adb_log)

    def test_invalid_shard_is_rejected_before_any_build_or_device_access(self):
        for shard in ("0/2", "3/2", "1", "a/b"):
            with self.subTest(shard=shard), tempfile.TemporaryDirectory() as temporary:
                repo = pathlib.Path(temporary)
                completed = self.run_wrapper(repo, "all", DEVICE_TEST_SHARD=shard)
                self.assertEqual(2, completed.returncode, completed.stderr)
                self.assertIn("DEVICE_TEST_SHARD", completed.stderr)
                self.assertFalse((repo / "gradle.log").exists())


if __name__ == "__main__":
    unittest.main()
