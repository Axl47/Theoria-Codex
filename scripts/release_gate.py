#!/usr/bin/env python3
"""Release metadata and recorded check results, keyed by the code that can affect them.

Without a subcommand, it decides whether the checked-out commit can be released and whether its code
already passed scripts/check.sh. The Release workflow runs this first, and the release skill runs it
before pushing. It prints the release identifiers, or writes them to GITHUB_OUTPUT. A missing result
is not an error: the workflow then runs the check itself.

Subcommands share the result refs with check.sh and the workflows:
  input-key [<commit>]          print the key naming every result for that commit's inputs
  record <kind> [<commit>]      push refs/<kind ref>/<key> to origin
  has <kind> [<commit>]         exit 0 when that result exists on origin, 1 otherwise
where kind is check (scripts/check.sh), release (check.sh --part release) or device (emulator tests).
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import os
import pathlib
import re
import subprocess
import sys


APP_BUILD = "app/build.gradle.kts"
VERSION_CODE_BASE = 1_500_000_000
VERSION_LINE = re.compile(r"^[+-]\s*version(Name|Code)\s*=")
RELEASE_PATH_PREFIXES = ("release-notes/", "app/src/release/generated/baselineProfiles/")
RESULT_REFS = {
    "check": "refs/verified",
    "release": "refs/verified-release",
    "device": "refs/verified-device",
}


class ReleaseError(Exception):
    """A release metadata problem the person preparing the release has to fix."""


@dataclasses.dataclass(frozen=True)
class Release:
    version: tuple[int, int, int]

    @property
    def name(self) -> str:
        return ".".join(map(str, self.version))

    @property
    def tag(self) -> str:
        return f"v{self.name}"

    @property
    def notes_file(self) -> str:
        return f"release-notes/{self.tag}.md"


def parse_release(build_text: str) -> Release:
    """Read the declared version and require the Android code the updater's ordering depends on."""
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', build_text, re.MULTILINE)
    code = re.search(r"^\s*versionCode\s*=\s*([0-9_]+)", build_text, re.MULTILINE)
    if name is None or code is None:
        raise ReleaseError(f"Could not read versionName and versionCode from {APP_BUILD}.")
    if re.fullmatch(r"\d{1,4}\.\d{1,2}\.\d{1,2}", name.group(1)) is None:
        raise ReleaseError(f"versionName {name.group(1)} must be X.Y.Z with minor and patch below 100.")
    major, minor, patch = map(int, name.group(1).split("."))
    expected = VERSION_CODE_BASE + major * 10_000 + minor * 100 + patch
    if int(code.group(1).replace("_", "")) != expected:
        raise ReleaseError(f"versionCode must be {expected} for {name.group(1)}.")
    return Release((major, minor, patch))


def require_newer_than_published(release: Release, tags: list[str]) -> None:
    published = [
        tuple(map(int, match.groups()))
        for match in (re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)", tag) for tag in tags)
        if match is not None
    ]
    if published and release.version <= max(published):
        newest = ".".join(map(str, max(published)))
        raise ReleaseError(f"{release.name} must be newer than the published {newest}.")


def is_release_only(changed_paths: list[str], build_diff: str) -> bool:
    """True when a commit cannot change what scripts/check.sh verifies, beyond the version itself."""
    for path in changed_paths:
        if path == APP_BUILD:
            changed = [
                line for line in build_diff.splitlines()
                if line[:1] in "+-" and not line.startswith(("+++", "---"))
            ]
            if not all(VERSION_LINE.match(line) for line in changed):
                return False
        elif path != "CHANGELOG.md" and not path.startswith(RELEASE_PATH_PREFIXES):
            return False
    return True


def git(*arguments: str) -> str:
    return subprocess.run(["git", *arguments], check=True, capture_output=True, text=True).stdout.strip()


def affects_checks(path: str) -> bool:
    """False only for files no build or check reads: prose outside source trees, release notes, and
    workflows other than Verify. No check reads Markdown (jscpd scans only Kotlin)."""
    if path.startswith(".github/"):
        return path == ".github/workflows/verify.yml"
    if path.startswith("release-notes/"):
        return False
    return not path.endswith(".md") or "/src/" in f"/{path}"


def input_key(listing: str) -> str:
    """Hash the NUL-separated `git ls-tree -r -z` entries that can affect the build or the checks."""
    entries = sorted(
        entry for entry in listing.split("\0") if entry and affects_checks(entry.split("\t", 1)[1])
    )
    return hashlib.sha256("\0".join(entries).encode()).hexdigest()


def commit_key(commit: str) -> str:
    return input_key(git("ls-tree", "-r", "-z", "--full-tree", commit))


def result_ref(kind: str, commit: str) -> str:
    return f"{RESULT_REFS[kind]}/{commit_key(commit)}"


def is_recorded(kind: str, commit: str) -> bool:
    return bool(git("ls-remote", "origin", result_ref(kind, commit)))


def is_verified(kind: str, commit: str = "HEAD") -> bool:
    """The commit's inputs passed, or it only adds release files to a commit whose inputs did."""
    if is_recorded(kind, commit):
        return True
    try:
        changed = git("diff", "--name-only", f"{commit}^", commit).splitlines()
        build_diff = git("diff", "--unified=0", f"{commit}^", commit, "--", APP_BUILD)
    except subprocess.CalledProcessError:  # A root commit, or a shallow clone without the parent.
        return False
    return is_release_only(changed, build_diff) and is_recorded(kind, f"{commit}^")


def run_result_command(arguments: argparse.Namespace) -> int:
    if arguments.command == "input-key":
        print(commit_key(arguments.commit))
        return 0
    if arguments.command == "record":
        ref = result_ref(arguments.kind, arguments.commit)
        commit = git("rev-parse", "--verify", f"{arguments.commit}^{{commit}}")
        git("push", "--quiet", "origin", f"+{commit}:{ref}")
        print(f"Recorded {ref}.")
        return 0
    return 0 if is_verified(arguments.kind, arguments.commit) else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--rehearsal",
        action="store_true",
        help="Allow a version that is already published, for runs that cannot publish.",
    )
    commands = parser.add_subparsers(dest="command")
    key_command = commands.add_parser("input-key", help="Print the result key for a commit.")
    key_command.add_argument("commit", nargs="?", default="HEAD")
    for name, help_text in [("record", "Push a result ref."), ("has", "Exit 0 when a result exists.")]:
        command = commands.add_parser(name, help=help_text)
        command.add_argument("kind", choices=sorted(RESULT_REFS))
        command.add_argument("commit", nargs="?", default="HEAD")
    arguments = parser.parse_args()
    if arguments.command is not None:
        return run_result_command(arguments)
    try:
        release = parse_release(pathlib.Path(APP_BUILD).read_text(encoding="utf-8"))
        notes = pathlib.Path(release.notes_file)
        if not notes.is_file() or not notes.read_text(encoding="utf-8").strip():
            raise ReleaseError(f"Missing release notes: {release.notes_file}")
        if not arguments.rehearsal:
            refs = git("ls-remote", "--tags", "--refs", "origin", "v*").splitlines()
            require_newer_than_published(release, [ref.rsplit("/", 1)[1] for ref in refs])
    except ReleaseError as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1

    outputs = {
        "version": release.name,
        "tag": release.tag,
        "notes-file": release.notes_file,
        "verified": str(is_verified("check")).lower(),
    }
    text = "".join(f"{key}={value}\n" for key, value in outputs.items())
    if output_path := os.environ.get("GITHUB_OUTPUT"):
        with open(output_path, "a", encoding="utf-8") as output:
            output.write(text)
    else:
        print(text, end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
