import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


REPO = Path(__file__).resolve().parents[2]


class CiEmulatorEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="theoria ci emulator ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in ("verify_ci_emulator.sh", "device_validation_common.sh", "prepare_ci_android_sdk.sh"):
            shutil.copyfile(REPO / "scripts" / name, scripts / name)
        self.executable(scripts / "verify_debug_device.sh", "#!/bin/sh\nprintf '%s' \"$*\" > delegated\nexit \"${FAKE_TEST_EXIT:-0}\"\n")
        self.executable(scripts / "verify_release_acceptance_device.sh", "#!/bin/sh\necho acceptance > delegated\n")
        self.avd = self.root / "avd"
        (self.avd / "test.avd").mkdir(parents=True)
        (self.avd / "test.ini").write_text("target=android-37.0\n")
        (self.avd / "test.avd/config.ini").write_text("hw.ramSize=4096M\ndisk.dataPartition.size=6G\n")
        self.executable(self.root / "fake-adb", textwrap.dedent('''\
            #!/usr/bin/env python3
            import os,sys,time
            args=sys.argv[1:]
            serial=os.environ.get('FAKE_SERIAL','emulator-5554')
            if args==['devices']:
                print('List of devices attached\\n'+serial+'\\tdevice')
                sys.exit(0)
            assert args[:2]==['-s',serial],args
            args=args[2:]
            if args[:1]==['logcat']:
                print('system log fixture',flush=True)
                time.sleep(60)
            elif args==['shell','getprop','ro.kernel.qemu']:print('1')
            elif args==['shell','getprop','ro.build.version.sdk']:print('37')
            elif args==['shell','am','get-current-user']:print('0')
            elif args==['shell','am','get-started-user-state','0']:print('RUNNING_UNLOCKED')
            elif args==['shell','pm','path','android']:print('package:/system/framework/framework-res.apk')
            elif args==['shell','cat','/proc/meminfo']:print('MemTotal: '+os.environ.get('FAKE_MEMORY','4000000')+' kB')
            elif args==['shell','df','-k','/data','/sdcard']:print('Filesystem 1K-blocks Used Available Use% Mounted on\\n/data 6000000 1000000 5000000 17% /data\\n/sdcard 6000000 1000000 5000000 17% /sdcard')
            elif args==['shell','dumpsys','dropbox','--print','system_server_crash']:print('crash fixture')
            else:raise AssertionError(args)
        '''))
        self.environment = {**os.environ, "GITHUB_ACTIONS": "true", "ANDROID_AVD_HOME": str(self.avd),
                            "ADB": str(self.root / "fake-adb"), "ANDROID_SERIAL": "emulator-5554"}

    def executable(self, path, contents):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
        path.chmod(0o755)

    def run_wrapper(self, **environment):
        return subprocess.run(["bash", "scripts/verify_ci_emulator.sh", "debug"], cwd=self.root,
                              env={**self.environment, **environment}, text=True, capture_output=True, timeout=10)

    def test_healthy_emulator_delegates_without_hiding_test_failure(self):
        result = self.run_wrapper(FAKE_TEST_EXIT="23")
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertEqual("all", (self.root / "delegated").read_text())
        evidence = self.root / "build/reports/ci-emulator/debug"
        for name in ("avd.ini", "avd-config.ini", "logcat.txt", "memory-before.txt", "memory-after.txt", "system-server-crash.txt"):
            self.assertTrue((evidence / name).is_file(), name)

    def test_misparsed_minor_api_target_is_rejected_before_installation(self):
        (self.avd / "test.ini").write_text("target=android-0\n")
        result = self.run_wrapper()
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("AVD target does not match", result.stderr)
        self.assertFalse((self.root / "delegated").exists())

    def test_api_37_rejects_underprovisioned_memory_before_installation(self):
        result = self.run_wrapper(FAKE_MEMORY="1495952")
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("requires a 4 GiB AVD", result.stderr)
        self.assertFalse((self.root / "delegated").exists())

    def test_physical_device_is_refused_even_in_ci(self):
        result = self.run_wrapper(FAKE_SERIAL="physical-phone", ANDROID_SERIAL="physical-phone")
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertFalse((self.root / "delegated").exists())
        self.assertFalse((self.root / "build").exists())

    def test_pinned_tools_replace_the_action_hardcoded_latest_path(self):
        sdk = self.root / "sdk"
        runner_temp = self.root / "runner-temp"
        runner_temp.mkdir()
        old_manager = sdk / "cmdline-tools/latest/bin/sdkmanager"
        self.executable(old_manager, textwrap.dedent('''\
            #!/usr/bin/env python3
            import os,pathlib,sys
            assert sys.argv[1:]==['--install','cmdline-tools;22.0']
            root=pathlib.Path(os.environ['ANDROID_HOME'])/'cmdline-tools/22.0/bin'
            root.mkdir(parents=True,exist_ok=True)
            for name in ['sdkmanager','avdmanager']:
                path=root/name
                path.write_text('#!/bin/sh\\necho 22.0\\n')
                path.chmod(0o755)
        '''))
        result = subprocess.run(["bash", "scripts/prepare_ci_android_sdk.sh"], cwd=self.root,
                                env={**self.environment, "ANDROID_HOME": str(sdk), "RUNNER_TEMP": str(runner_temp)},
                                text=True, capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("22.0", result.stdout.strip())
        self.assertEqual((sdk / "cmdline-tools/22.0").resolve(), (sdk / "cmdline-tools/latest").resolve())
        self.assertTrue((runner_temp / "android-command-line-tools-preinstalled/bin/sdkmanager").is_file())


if __name__ == "__main__":
    unittest.main()
