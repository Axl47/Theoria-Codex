"""Run the real debug wrapper against fake tools; no SDK, APK or device is used."""
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class DebugJourneyDiagnosticsTest(unittest.TestCase):
    def test_collects_after_success_and_failure_without_replacing_test_exit_status(self):
        for result in (0, 7):
            with self.subTest(result=result), tempfile.TemporaryDirectory() as temporary:
                repo = pathlib.Path(temporary)
                scripts = repo / "scripts"
                scripts.mkdir()
                for name in ("verify_debug_device.sh", "device_validation_common.sh"):
                    shutil.copyfile(ROOT / "scripts" / name, scripts / name)
                (scripts / "verify_device_apk.py").write_text("# Stub packaged-ID verification in this host-only wrapper test.\n")
                gradle = repo / "gradlew"
                gradle.write_text('''#!/bin/bash
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
                log = repo / "adb.log"
                env = os.environ | {"ADB": str(adb), "ANDROID_SERIAL": "emulator-fixture",
                                    "FAKE_TEST_STATUS": str(result), "FAKE_ADB_LOG": str(log)}
                completed = subprocess.run(["bash", str(scripts / "verify_debug_device.sh"), "app"],
                                           env=env, capture_output=True, text=True)
                self.assertEqual(result, completed.returncode, completed.stderr)
                self.assertIn("-s emulator-fixture pull /sdcard/Android/data/com.theoriacodex.debug/files/journey-diagnostics",
                              log.read_text())


if __name__ == "__main__":
    unittest.main()
