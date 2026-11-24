#!/usr/bin/env python3
"""ADR integrity: every record's "Applies to" and "Guarded by" sections still point at real code.

Code cannot point back to the ADRs that govern it (comments are banned), so a rename or deletion would leave a
record silently stale. Each bullet in either section reads `path`[, `path` ...][: `symbol`, `symbol` ...]: the
backticked paths before the first colon, the backticked symbols after it, and prose around them ignored. A
bullet with no backticked path before its first colon is prose and is skipped. A path without a module
directory resolves against the directories of the bullet's previous path.

"Applies to": a directory stands for every file under it, and a symbol is found when each identifier in it
(`Owner.member` holds two) appears as a whole word in one of the bullet's files.

"Guarded by" names the guards that keep the record's promises, so deleting, renaming, disabling or untagging one
fails here and names the record. Its paths are files. In a test file (TEST_FILE) each symbol is a test's exact
name: declared once in the bullet's files, run (`@Test` and no `@Ignore` above it, or a Python `test_` name)
and tagged with the record's number (`tags`). Any other file is a source guard: a symbol without whitespace
follows the "Applies to" rule, and one with whitespace is source text that must appear in a file, compared with
whitespace removed and `,)` read as `)`. A section with no bullet fails. In reverse, every tagged test in the
tracked test files must be listed by the live record its tag names. Superseded records are skipped.

With --base, a missing path that git sees as renamed or deleted since that revision gets an indented line naming
its new path, so the PR that moved the file names the records to update. --base also prints notes that never
change the exit status: the records whose "Applies to" covers a file the change edits, and guard tests whose
assertions changed alongside the code their record governs while the record did not (`guard_edit_notes`). Notes
are indented, like every line the Main policy workflow must not count as a violation.
"""

from __future__ import annotations

import argparse
import collections
import pathlib
import re
import subprocess
import sys
from dataclasses import dataclass

ADR_DIR = pathlib.Path("docs/adr")
ADR_NAME = re.compile(r"\d{4}-[a-z0-9-]+\.md")
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
TEST_FILE = re.compile(r".*/src/(?:test|androidTest)/.*\.(?:kt|java)|scripts/tests/test_[^/]*\.py")
DECLARATION = re.compile(
    r"\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|protected|private|internal|override|open|final|static|suspend|async)\s+)*"
    r"(?:fun|def|void)\s+(?:`([^`\n]+)`|([A-Za-z_]\w*))\s*\("
)
KOTLIN_TAG = re.compile(r"\(ADR (\d{4}(?:, \d{4})*)\)$")
PYTHON_TAG = re.compile(r"(?:_adr_\d{4})+$")
TEST_ANNOTATION = re.compile(r"@(?:[\w.]+\.)?Test\b")
IGNORE_ANNOTATION = re.compile(r"@(?:[\w.]+\.)?Ignore\b")
ASSERTION = re.compile(r"\b(?:(?:assert|expect|verify|fail)\w*\s*[(<{]|should\w*\b|(?:check|require)\()")
SIGNATURE_CHANGED = "the signature this record pins has changed: restore it, or edit the record if the decision changed."


@dataclass(frozen=True)
class Bullet:
    paths: list[str]
    symbols: list[str]


@dataclass(frozen=True)
class Record:
    name: str
    text: str

    @property
    def number(self) -> str:
        return pathlib.PurePath(self.name).name[:4]

    @property
    def superseded(self) -> bool:
        return re.search(r"^Status: Superseded", self.text, re.MULTILINE) is not None


def section(text: str, heading: str) -> list[str]:
    """The bullets of a record's section titled [heading], continuation lines joined."""
    bullets: list[str] = []
    inside = False
    for line in text.splitlines():
        if line.startswith("## "):
            inside = line.strip() == f"## {heading}"
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
        paths=[parts[index] for index in code if index < colon],
        symbols=[parts[index] for index in code if index > colon],
    )


def bullets(text: str, heading: str) -> list[Bullet]:
    """The parsed bullets of a section that name at least one path; prose bullets are left out."""
    return [bullet for bullet in map(parse_bullet, section(text, heading)) if bullet.paths]


def candidates(path: str, previous: pathlib.Path | None, root: pathlib.Path) -> list[pathlib.Path]:
    """Where [path] may live: the repository root first, then each ancestor of [previous]'s directory."""
    found = [root / path.rstrip("/")]
    if previous is not None:
        found += [directory / path.rstrip("/") for directory in previous.parents if directory.is_relative_to(root) and directory != root]
    return found


def missing_path_message(adr: str, path: str, places: list[str], moved: dict[str, str], deleted: set[str]) -> str:
    message = f"{adr}: `{path}` does not exist. Correct the path, or mark the record superseded if the code is gone (docs/adr/README.md)."
    if old := next((place for place in places if place in moved), None):
        return f"{message}\n  It moved to `{moved[old]}` in this change; point the record there."
    if any(place in deleted for place in places):
        return f"{message}\n  It was deleted in this change; point the record at what replaced it, or supersede the record."
    return message


def resolve(bullet: Bullet, adr: str, root: pathlib.Path, moved: dict[str, str], deleted: set[str]) -> tuple[list[pathlib.Path], list[str]]:
    """The existing places a bullet's paths name, and a problem for each path that does not exist."""
    found: list[pathlib.Path] = []
    problems: list[str] = []
    previous: pathlib.Path | None = None
    for path in bullet.paths:
        places = candidates(path, previous, root)
        resolved = next((place for place in places if place.exists()), None)
        if resolved is None:
            relative = [str(place.relative_to(root)) for place in places]
            problems.append(missing_path_message(adr, path, relative, moved, deleted))
            previous = next((root / place for place in relative if place in moved or place in deleted), previous or places[0])
            continue
        previous = resolved
        found.append(resolved)
    return found, problems


def missing_words(symbol: str, source: str) -> list[str]:
    words = set(IDENTIFIER.findall(source))
    return [word for word in IDENTIFIER.findall(symbol) if word not in words]


def applies_to_problems(record: Record, root: pathlib.Path, moved: dict[str, str], deleted: set[str]) -> list[str]:
    problems: list[str] = []
    for bullet in bullets(record.text, "Applies to"):
        places, missing = resolve(bullet, record.name, root, moved, deleted)
        problems += missing
        if missing:
            continue
        files = [file for place in places for file in (sorted(f for f in place.rglob("*") if f.is_file()) if place.is_dir() else [place])]
        source = "\n".join(file.read_text(encoding="utf-8", errors="replace") for file in files)
        where = ", ".join(f"`{path}`" for path in bullet.paths)
        problems += [
            f"{record.name}: `{symbol}` is not in {where}. Rename it here or move it to the file that now holds it."
            for symbol in bullet.symbols
            if missing_words(symbol, source)
        ]
    return problems


def tags(name: str) -> list[str]:
    """The record numbers a test name carries: `... (ADR 0008, 0022)` in Kotlin, `test_..._adr_0008_adr_0022` in Python."""
    if match := KOTLIN_TAG.search(name):
        return match.group(1).split(", ")
    if match := PYTHON_TAG.search(name):
        return re.findall(r"\d{4}", match.group())
    return []


def declarations(text: str) -> list[tuple[str, int]]:
    """Each function declared at the start of a line in a Kotlin, Java or Python file: its name and line index.

    Anchoring to the line start keeps declarations quoted inside string literals, such as test fixtures, out.
    """
    return [
        (match.group(1) or match.group(2), index)
        for index, line in enumerate(text.splitlines())
        if (match := DECLARATION.match(line))
    ]


def runs(path: pathlib.Path, lines: list[str], index: int, name: str) -> bool:
    """Whether the test declared at [index] runs: a Python `test_` method, or `@Test` and no `@Ignore` above it."""
    if path.suffix == ".py":
        return name.startswith("test_")
    above = [lines[index].split("fun ")[0].split("void ")[0]]
    for line in reversed(lines[:index]):
        if not line.strip().startswith("@"):
            break
        above.append(line)
    annotations = " ".join(above)
    return TEST_ANNOTATION.search(annotations) is not None and IGNORE_ANNOTATION.search(annotations) is None


def test_guard_problems(record: Record, files: list[pathlib.Path], root: pathlib.Path, symbol: str) -> list[str]:
    texts = {file: file.read_text(encoding="utf-8", errors="replace") for file in files}
    found = [(file, index) for file, text in texts.items() for name, index in declarations(text) if name == symbol]
    where = ", ".join(f"`{file.relative_to(root)}`" for file in files)
    if not found:
        return [f"{record.name}: guard `{symbol}` is not a test in {where}. Restore the test, or point the record at its new name."]
    if len(found) > 1:
        return [f"{record.name}: guard `{symbol}` is declared more than once in {where}; give each test its own name."]
    file, index = found[0]
    problems: list[str] = []
    if not runs(file, texts[file].splitlines(), index, symbol):
        problems.append(f"{record.name}: guard `{symbol}` in `{file.relative_to(root)}` does not run; a guard needs @Test and no @Ignore, or a Python name starting test_.")
    if record.number not in tags(symbol):
        problems.append(
            f"{record.name}: guard `{symbol}` does not carry this record's tag; end its name with (ADR {record.number}) in Kotlin "
            f"or _adr_{record.number} in Python, so its failure names the record."
        )
    return problems


def source_guard_problems(record: Record, bullet: Bullet, files: list[pathlib.Path], root: pathlib.Path) -> list[str]:
    texts = [file.read_text(encoding="utf-8", errors="replace") for file in files]
    where = ", ".join(f"`{file.relative_to(root)}`" for file in files)
    problems: list[str] = []
    for symbol in bullet.symbols:
        if any(character.isspace() for character in symbol):
            if not any(squeeze(symbol) in squeeze(text) for text in texts):
                problems.append(f"{record.name}: `{symbol}` is not in {where}; {SIGNATURE_CHANGED}")
        elif missing_words(symbol, "\n".join(texts)):
            problems.append(f"{record.name}: guard `{symbol}` is not in {where}. Restore it, or point the record at its new name.")
    return problems


def squeeze(text: str) -> str:
    """[text] without whitespace and with trailing commas before `)` dropped, so formatting never breaks a match."""
    return re.sub(r"\s+", "", text).replace(",)", ")")


def guarded_by(record: Record, root: pathlib.Path, moved: dict[str, str], deleted: set[str]) -> tuple[list[str], list[tuple[str, str]]]:
    """The record's "Guarded by" problems, and the (file, test name) pairs it lists as test guards."""
    if not re.search(r"^## Guarded by\s*$", record.text, re.MULTILINE):
        return [], []
    if not section(record.text, "Guarded by"):
        return [f"{record.name}: \"Guarded by\" lists nothing. Name a guard, or say why the record is left to prose (docs/adr/README.md)."], []
    problems: list[str] = []
    listed: list[tuple[str, str]] = []
    for bullet in bullets(record.text, "Guarded by"):
        files, missing = resolve(bullet, record.name, root, moved, deleted)
        problems += missing
        if missing:
            continue
        if folders := [file for file in files if file.is_dir()]:
            problems += [f"{record.name}: guard path `{folder.relative_to(root)}` is a directory; name the file that holds the guard." for folder in folders]
            continue
        tests = [TEST_FILE.fullmatch(file.relative_to(root).as_posix()) is not None for file in files]
        if all(tests):
            for symbol in bullet.symbols:
                problems += test_guard_problems(record, files, root, symbol)
                listed += [(file.relative_to(root).as_posix(), symbol) for file in files]
        elif any(tests):
            problems.append(f"{record.name}: a \"Guarded by\" line mixes test and source files; list them on separate lines.")
        else:
            problems += source_guard_problems(record, bullet, files, root)
    return problems, listed


def untracked_tags(records: list[Record], listed: set[tuple[str, str, str]], test_files: list[str], root: pathlib.Path) -> list[str]:
    """A problem for each tagged test in [test_files] that the record its tag names does not list."""
    by_number = collections.defaultdict(list)
    for record in records:
        by_number[record.number].append(record)
    problems: list[str] = []
    for path in test_files:
        for name, _ in declarations((root / path).read_text(encoding="utf-8", errors="replace")):
            for number in tags(name):
                live = [record for record in by_number[number] if not record.superseded]
                if any((record.name, path, name) in listed for record in live):
                    continue
                if live:
                    owners = " or ".join(record.name for record in live)
                    problems.append(f"{path}: test `{name}` carries the tag of {owners}, which does not list it under \"Guarded by\". List it there, or drop the tag.")
                elif by_number[number]:
                    problems.append(f"{path}: test `{name}` guards a superseded record (ADR {number}); move the guard to the record that replaced it, or drop the tag.")
                else:
                    problems.append(f"{path}: test `{name}` carries the tag of ADR {number}, which does not exist. Correct the tag.")
    return problems


def records(root: pathlib.Path) -> list[Record]:
    paths = sorted(path for path in (root / ADR_DIR).glob("*.md") if ADR_NAME.fullmatch(path.name))
    return [Record(str(path.relative_to(root)), path.read_text(encoding="utf-8")) for path in paths]


def check(root: pathlib.Path, moved: dict[str, str] | None = None, deleted: set[str] | None = None, test_files: list[str] | None = None) -> list[str]:
    """Every problem in the records under [root]; tagged tests are looked for only in [test_files]."""
    every = records(root)
    problems: list[str] = []
    listed: set[tuple[str, str, str]] = set()
    for record in every:
        if record.superseded:
            continue
        problems += applies_to_problems(record, root, moved or {}, deleted or set())
        found, guards = guarded_by(record, root, moved or {}, deleted or set())
        problems += found
        listed |= {(record.name, path, name) for path, name in guards}
    return problems + untracked_tags(every, listed, test_files or [], root)


def test_span(lines: list[str], start: int) -> list[str]:
    """A test's lines: its declaration through the lines indented deeper, plus a closing `}` or `)` line."""
    depth = len(lines[start]) - len(lines[start].lstrip())
    span = [lines[start]]
    for line in lines[start + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) <= depth:
            if line.lstrip()[0] in "})":
                span.append(line)
            break
        span.append(line)
    return span


def assertion_lines(text: str, name: str) -> collections.Counter[str] | None:
    """The stripped assertion lines of test [name] in [text], or None when it is not declared exactly once."""
    lines = text.splitlines()
    found = [index for declared, index in declarations(text) if declared == name]
    if len(found) != 1:
        return None
    return collections.Counter(line.strip() for line in test_span(lines, found[0]) if ASSERTION.search(line))


def assertions_changed(old: str, new: str, name: str) -> bool:
    """Whether test [name] exists in both texts and its assertion lines differ; edits elsewhere in its body do not count."""
    before, after = assertion_lines(old, name), assertion_lines(new, name)
    return before is not None and after is not None and before != after


def git(root: pathlib.Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], cwd=root, capture_output=True, text=True)


def changed_files(root: pathlib.Path, revision: str) -> set[str]:
    """Tracked paths that differ between [revision] and the work tree."""
    return {path for path in git(root, "diff", "--name-only", "-z", revision, "--").stdout.split("\0") if path}


def governed(record: Record, root: pathlib.Path, changed: set[str]) -> list[str]:
    """The [changed] paths that the record's "Applies to" names, a directory covering the files under it."""
    places = [
        place.relative_to(root).as_posix()
        for bullet in bullets(record.text, "Applies to")
        for place in resolve(bullet, record.name, root, {}, set())[0]
    ]
    return sorted(path for path in changed if any(path == place or path.startswith(place + "/") for place in places))


def governing_notes(root: pathlib.Path, revision: str) -> list[str]:
    """One note naming the live records that govern code changed since [revision], unless the change edits them."""
    changed = changed_files(root, revision)
    names = [record.name for record in records(root) if not record.superseded and record.name not in changed and governed(record, root, changed)]
    return [f"  note: this change edits code governed by {', '.join(names)}; reread them."] if names else []


def guard_edit_notes(root: pathlib.Path, revision: str) -> list[str]:
    """A note per guard test whose assertions changed since [revision] beside code its unchanged record governs."""
    changed = changed_files(root, revision)
    notes: list[str] = []
    for record in records(root):
        if record.superseded or record.name in changed:
            continue
        for path, name in guarded_by(record, root, {}, set())[1]:
            code = [file for file in governed(record, root, changed) if file != path]
            if path not in changed or not code:
                continue
            old = git(root, "show", f"{revision}:{path}")
            if old.returncode == 0 and assertions_changed(old.stdout, (root / path).read_text(encoding="utf-8"), name):
                notes.append(
                    f"  note: {record.name}: this change edits the assertions of guard `{name}` and code the record governs ({code[0]}). "
                    "If the promise still holds, restore the guard; if the decision changed, edit the record."
                )
    return notes


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


def tracked_tests(root: pathlib.Path) -> list[str]:
    listed = subprocess.run(
        ["git", "ls-files", "-z", "--", "*/src/test/*", "*/src/androidTest/*", "scripts/tests/*"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.split("\0")
    return [path for path in listed if TEST_FILE.fullmatch(path) and (root / path).is_file()]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", help="revision that renames, deletions and the notes are found against")
    args = parser.parse_args()
    root = pathlib.Path.cwd()
    moved, deleted = changes(args.base) if args.base else ({}, set())
    problems = check(root, moved, deleted, tracked_tests(root))
    notes = governing_notes(root, args.base) + guard_edit_notes(root, args.base) if args.base else []
    for line in problems + notes:
        print(line, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
