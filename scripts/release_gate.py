#!/usr/bin/env python3
"""Decide whether the checked-out commit can be released, and whether its code is already verified.

The Release workflow runs this first, and the release skill runs it before pushing. It prints the
release identifiers, or writes them to GITHUB_OUTPUT. A missing verification is not an error: the
workflow then runs the full check itself.
"""
from __future__ import annotations

import argparse
import dataclasses
import os
import pathlib
import re
import subprocess
import sys


APP_BUILD = "app/build.gradle.kts"
VERSION_CODE_BASE = 1_500_000_000
VERSION_LINE = re.compile(r"^[+-]\s*version(Name|Code)\s*=")
RELEASE_PATH_PREFIXES = ("release-notes/", "app/src/release/generated/baselineProfiles/")


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


def is_recorded(commit: str) -> bool:
    tree = git("rev-parse", f"{commit}^{{tree}}")
    return bool(git("ls-remote", "origin", f"refs/verified/{tree}"))


def is_verified() -> bool:
    """The commit passed the check itself, or it only adds release files to a commit that did."""
    if is_recorded("HEAD"):
        return True
    changed = git("diff", "--name-only", "HEAD^", "HEAD").splitlines()
    build_diff = git("diff", "--unified=0", "HEAD^", "HEAD", "--", APP_BUILD)
    return is_release_only(changed, build_diff) and is_recorded("HEAD^")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--rehearsal",
        action="store_true",
        help="Allow a version that is already published, for runs that cannot publish.",
    )
    arguments = parser.parse_args()
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
        "verified": str(is_verified()).lower(),
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
