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
  *) exit "$FAKE_TEST_STATUS" ;;
esac
''')
        adb = repo / "adb"
        adb.write_text('''#!/bin/bash
if [[ "$1" == devices ]]; then
  printf 'List of devices attached\\nemulator-fixture\\tdevice\\n'
else
  printf '%s\\n' "$*" >> "$FAKE_ADB_LOG"
  exit 1 # Missing diagnostics must not replace the instrumentation result.
fi
''')
        gradle.chmod(0o755)
        adb.chmod(0o755)
        env = os.environ | {"ADB": str(adb), "ANDROID_SERIAL": "emulator-fixture",
                            "FAKE_TEST_STATUS": str(test_status), "FAKE_ADB_LOG": str(repo / "adb.log"),
                            "FAKE_GRADLE_LOG": str(repo / "gradle.log")} | environment
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
                self.assertIn(":app:connectedDebugAndroidTest :core-data-android:connectedDebugAndroidTest", line)
                self.assertIn("-Pandroid.testInstrumentationRunnerArguments.numShards=2", line)
                self.assertIn("-Pandroid.testInstrumentationRunnerArguments.shardIndex=1", line)

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
