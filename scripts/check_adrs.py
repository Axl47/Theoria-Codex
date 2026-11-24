#!/usr/bin/env python3
"""ADR integrity: every path an ADR's "Applies to" section names exists, and every symbol it names appears there.

Code cannot point back to the ADRs that govern it (comments are banned), so a rename or deletion would leave a
record silently stale. Each bullet under "## Applies to" reads `path`[, `path` ...][: `symbol`, `symbol` ...]:
the backticked paths before the first colon, the backticked symbols after it, and prose around them ignored. A
path without a module directory resolves against the directories of the bullet's previous path. A symbol is
found when each identifier in it (`Owner.member` holds two) appears as a whole word in one of the bullet's
files. Superseded records are skipped.

With --base, a missing path that git sees as renamed or deleted since that revision is reported with its new
path, so the PR that moved the file names the records to update.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys
from dataclasses import dataclass

ADR_DIR = pathlib.Path("docs/adr")
ADR_NAME = re.compile(r"\d{4}-[a-z0-9-]+\.md")
PATH_TOKEN = re.compile(r"[\w./-]*\.(kt|kts|java|xml|py|sh|json|yml|yaml|toml|properties|pro|md|txt|gradle)|[\w.-]+(/[\w.-]+)+/?")
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
NUMBERING = "Number a new ADR as the highest on the latest main plus one, and rebase right before pushing."


@dataclass(frozen=True)
class Bullet:
    paths: list[str]
    symbols: list[str]


def applies_to(text: str) -> list[str]:
    """The bullets of a record's "Applies to" section, continuation lines joined."""
    bullets: list[str] = []
    inside = False
    for line in text.splitlines():
        if line.startswith("## "):
            inside = line.strip() == "## Applies to"
        elif inside and line.startswith("- "):
            bullets.append(line[2:])
        elif inside and line[:1].isspace() and line.strip() and bullets:
            bullets[-1] += " " + line.strip()
    return bullets


def parse_bullet(bullet: str) -> Bullet:
    parts = bullet.split("`")
    code = [index for index in range(1, len(parts), 2)]
    colon = next((index for index in range(0, len(parts), 2) if ":" in parts[index]), len(parts))
    return Bullet(
        paths=[parts[index] for index in code if index < colon and PATH_TOKEN.fullmatch(parts[index])],
        symbols=[parts[index] for index in code if index > colon],
    )


def resolve(path: str, previous: pathlib.Path | None, root: pathlib.Path) -> pathlib.Path:
    """[path] from the repository root, or from an ancestor of [previous]'s directory when only that exists."""
    direct = root / path.rstrip("/")
    if direct.exists() or previous is None:
        return direct
    for directory in previous.parents:
        candidate = directory / path
        if candidate.exists():
            return candidate
        if directory == root:
            break
    return direct


def missing_path_message(adr: str, path: str, moved: dict[str, str], deleted: set[str]) -> str:
    if path in moved:
        return f"{adr}: `{path}` moved to `{moved[path]}`. Update this record's Applies to."
    if path in deleted:
        return f"{adr}: `{path}` was deleted. Point this record at the code that replaced it, or mark it superseded (docs/adr/README.md)."
    return f"{adr}: `{path}` does not exist. Correct the path, or mark the record superseded if the code is gone (docs/adr/README.md)."


def check_record(adr: pathlib.Path, root: pathlib.Path, moved: dict[str, str], deleted: set[str]) -> list[str]:
    text = adr.read_text(encoding="utf-8")
    name = str(adr.relative_to(root))
    if re.search(r"^Status: Superseded", text, re.MULTILINE):
        return []
    problems: list[str] = []
    for line in applies_to(text):
        bullet = parse_bullet(line)
        files: list[pathlib.Path] = []
        previous: pathlib.Path | None = None
        for path in bullet.paths:
            resolved = resolve(path, previous, root)
            if not resolved.exists():
                problems.append(missing_path_message(name, path, moved, deleted))
                continue
            previous = resolved
            if resolved.is_file():
                files.append(resolved)
        if not files or len(files) < len(bullet.paths):
            continue
        source = "\n".join(file.read_text(encoding="utf-8", errors="replace") for file in files)
        words = set(IDENTIFIER.findall(source))
        for symbol in bullet.symbols:
            absent = [word for word in IDENTIFIER.findall(symbol) if word not in words]
            if absent:
                where = ", ".join(f"`{path}`" for path in bullet.paths)
                problems.append(f"{name}: `{symbol}` is not in {where}. Rename it here or move it to the file that now holds it.")
    return problems


def numbering_problems(records: list[pathlib.Path]) -> list[str]:
    seen: dict[str, str] = {}
    problems = []
    for record in records:
        number = record.name[:4]
        if number in seen:
            problems.append(f"docs/adr: {seen[number]} and {record.name} share number {number}. {NUMBERING}")
        seen.setdefault(number, record.name)
    return problems


def check(root: pathlib.Path, moved: dict[str, str] | None = None, deleted: set[str] | None = None) -> list[str]:
    records = sorted(path for path in (root / ADR_DIR).glob("*.md") if ADR_NAME.fullmatch(path.name))
    problems = numbering_problems(records)
    for record in records:
        problems.extend(check_record(record, root, moved or {}, deleted or set()))
    return problems


def changes(revision: str) -> tuple[dict[str, str], set[str]]:
    """Renamed (old to new) and deleted tracked paths between [revision] and the work tree."""
    listed = subprocess.run(
        ["git", "diff", "--find-renames", "--name-status", "--diff-filter=RD", "-z", revision, "--"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.split("\0")
    moved: dict[str, str] = {}
    deleted: set[str] = set()
    index = 0
    while index < len(listed) - 1:
        status = listed[index]
        if status.startswith("R"):
            moved[listed[index + 1]] = listed[index + 2]
            index += 3
        else:
            deleted.add(listed[index + 1])
            index += 2
    return moved, deleted


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", help="revision that renames and deletions are found against")
    args = parser.parse_args()
    moved, deleted = changes(args.base) if args.base else ({}, set())
    problems = check(pathlib.Path.cwd(), moved, deleted)
    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
