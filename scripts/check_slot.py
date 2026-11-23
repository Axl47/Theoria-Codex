#!/usr/bin/env python3
"""Hold one slot in a host-local N-slot flock semaphore for heavy local checks.

Running the lock holder in its own short-lived process keeps lock file descriptors
out of persistent Gradle and Kotlin daemons, and works on both Linux and macOS.
"""
from __future__ import annotations

import argparse
import fcntl
import os
import pathlib
import signal
import sys
import time


class _StopRequested(BaseException):
    """Raised from SIGTERM/SIGINT handlers to interrupt sleeps immediately."""


def _parent_alive(expected_ppid: int) -> bool:
    return expected_ppid <= 0 or os.getppid() == expected_ppid


def acquire_slot(
    lock_dir: pathlib.Path,
    slots: int,
    parent_pid: int,
    poll_seconds: float,
) -> tuple[int, int] | None:
    """Acquire an exclusive flock on slot-1..slot-N and return (slot_number, fd), or None if orphaned."""
    lock_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    announced = False
    while True:
        if not _parent_alive(parent_pid):
            return None
        for slot in range(1, slots + 1):
            lock_path = lock_dir / f"slot-{slot}.lock"
            fd = os.open(lock_path, os.O_CREAT | os.O_RDWR, 0o600)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                os.close(fd)
                continue
            except OSError:
                os.close(fd)
                raise
            return slot, fd
        if not announced:
            print(
                f"Waiting for a local check slot ({slots} max concurrent runs; "
                f"lock dir {lock_dir}; set THEORIA_CHECK_SLOTS=0 to bypass)...",
                file=sys.stderr,
                flush=True,
            )
            announced = True
        time.sleep(poll_seconds)


def _write_ready_file(ready_file: pathlib.Path, slot: int) -> None:
    tmp_path = ready_file.with_suffix(f"{ready_file.suffix}.{os.getpid()}.tmp")
    tmp_path.write_text(f"{slot}\n", encoding="utf-8")
    os.replace(tmp_path, ready_file)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dir", required=True, type=pathlib.Path)
    parser.add_argument("--slots", type=int, default=2)
    parser.add_argument("--ready-file", required=True, type=pathlib.Path)
    parser.add_argument("--parent-pid", type=int, default=0)
    parser.add_argument("--poll-seconds", type=float, default=1.0)
    args = parser.parse_args(argv)

    if args.slots < 1:
        parser.error("--slots must be >= 1")
    if args.poll_seconds <= 0:
        parser.error("--poll-seconds must be > 0")

    def _stop(_signum: int, _frame: object) -> None:
        raise _StopRequested()

    signal.signal(signal.SIGTERM, _stop)
    signal.signal(signal.SIGINT, _stop)

    fd: int | None = None
    try:
        if not _parent_alive(args.parent_pid):
            return 0
        acquired = acquire_slot(args.dir, args.slots, args.parent_pid, args.poll_seconds)
        if acquired is None:
            return 0
        slot, fd = acquired
        _write_ready_file(args.ready_file, slot)
        while _parent_alive(args.parent_pid):
            time.sleep(args.poll_seconds)
    except _StopRequested:
        return 0
    finally:
        if fd is not None:
            os.close(fd)
    return 0


if __name__ == "__main__":
    sys.exit(main())
