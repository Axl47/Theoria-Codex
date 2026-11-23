from __future__ import annotations

import pathlib
import subprocess
import sys
import tempfile
import time
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
CHECK_SLOT = REPOSITORY_ROOT / "scripts" / "check_slot.py"


def _wait_for_file(path: pathlib.Path, timeout_seconds: float = 5.0) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if path.is_file():
            return True
        time.sleep(0.02)
    return path.is_file()


class CheckSlotTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.lock_dir = self.root / "slots"
        self.procs: list[subprocess.Popen[str]] = []

    def tearDown(self) -> None:
        for proc in self.procs:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=3)
            if proc.stderr is not None:
                proc.stderr.close()

    def _spawn(
        self,
        ready_name: str,
        slots: int = 2,
        parent_pid: int | None = None,
    ) -> tuple[subprocess.Popen[str], pathlib.Path]:
        ready = self.root / ready_name
        cmd = [
            sys.executable,
            str(CHECK_SLOT),
            "--dir",
            str(self.lock_dir),
            "--slots",
            str(slots),
            "--ready-file",
            str(ready),
            "--poll-seconds",
            "0.05",
        ]
        if parent_pid is not None:
            cmd.extend(["--parent-pid", str(parent_pid)])
        proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
        self.procs.append(proc)
        return proc, ready

    def test_allows_two_concurrent_holders_and_queues_third_until_release(self) -> None:
        first, ready1 = self._spawn("r1", slots=2)
        second, ready2 = self._spawn("r2", slots=2)
        self.assertTrue(_wait_for_file(ready1))
        self.assertTrue(_wait_for_file(ready2))
        self.assertEqual({"1", "2"}, {ready1.read_text().strip(), ready2.read_text().strip()})

        third, ready3 = self._spawn("r3", slots=2)
        time.sleep(0.2)
        self.assertFalse(ready3.exists(), "Third holder should wait while both slots are held")

        first.terminate()
        self.assertEqual(0, first.wait(timeout=3))
        self.assertTrue(_wait_for_file(ready3), "Third holder should acquire the freed slot")
        self.assertEqual(ready1.read_text().strip(), ready3.read_text().strip())

        second.terminate()
        third.terminate()
        self.assertEqual(0, second.wait(timeout=3))
        _, third_stderr = third.communicate(timeout=3)
        self.assertEqual(0, third.returncode)
        self.assertIn("Waiting for a local check slot (2 max concurrent runs;", third_stderr)
        self.assertIn("THEORIA_CHECK_SLOTS=0", third_stderr)

    def test_exits_immediately_when_terminated_while_waiting(self) -> None:
        first, ready1 = self._spawn("r1", slots=1)
        self.assertTrue(_wait_for_file(ready1))

        waiting, ready2 = self._spawn("r2", slots=1)
        time.sleep(0.15)
        self.assertFalse(ready2.exists())

        waiting.terminate()
        self.assertEqual(0, waiting.wait(timeout=3))
        self.assertFalse(ready2.exists())

    def test_exits_when_expected_parent_pid_does_not_match(self) -> None:
        # Passing a non-matching parent PID simulates an orphaned holder.
        proc, ready = self._spawn("orphan", slots=1, parent_pid=1)
        self.assertEqual(0, proc.wait(timeout=3))
        self.assertFalse(ready.exists())


if __name__ == "__main__":
    unittest.main()
