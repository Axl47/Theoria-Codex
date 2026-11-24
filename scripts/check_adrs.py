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
fails here and names the record. Its paths are files, and each line with a path names at least one symbol. In a
test file (TEST_FILE) each symbol is a test's exact name: declared once in the bullet's files, run (`runs`) and
tagged with the record's number (`tags`). Any other file is a source guard: a symbol without whitespace follows
the "Applies to" rule, and one with whitespace is source text (`pinned`). A line that names no path must start
"Left to prose:" and give a reason, so a forgotten backtick cannot turn a guard into prose. A section with no
bullet fails. Every live record has the section unless docs/adr/unguarded.txt lists it, a list that only shrinks
(`unguarded_problems`). In reverse, every tagged test in the tracked test files must be listed by the live record
its tag names, and a name that looks tagged but is not fails. Superseded records are skipped.

With --base, a missing path that git sees as renamed or deleted since that revision gets an indented line naming
its new path, so the PR that moved the file names the records to update. --base also prints notes that never
change the exit status: the records whose "Applies to" lines the change's edited lines touch (`touches`), and
guard tests edited alongside the code their record governs while the record did not (`guard_edit_notes`). Notes
are indented, like every line the Main policy workflow must not count as a violation.
"""

from __future__ import annotations

import argparse
import collections
import collections.abc
import pathlib
import re
import subprocess
import sys
from dataclasses import dataclass

ADR_DIR = pathlib.Path("docs/adr")
ADR_NAME = re.compile(r"\d{4}-[a-z0-9-]+\.md")
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
TEST_FILE = re.compile(r".*/src/(?:test|androidTest|sharedTest)\w*/.*\.(?:kt|java)|scripts/tests/test_[^/]*\.py")
DECLARATION = re.compile(
    r"\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|protected|private|internal|override|open|final|static|suspend|async)\s+)*"
    r"(?:fun|def|void)\s+(?:`([^`\n]+)`|([A-Za-z_]\w*))\s*\("
)
KOTLIN_TAG = re.compile(r"\(ADR (\d{4}(?:, \d{4})*)\)$")
PYTHON_TAG = re.compile(r"(?:_adr_\d{4})+$")
MALFORMED_TAG = re.compile(r"\(ADR|_adr_", re.IGNORECASE)
TEST_ANNOTATION = re.compile(r"@(?:[\w.]+\.)?Test\b(\s*\([^)]*\))?")
IGNORE_ANNOTATION = re.compile(r"@(?:[\w.]+\.)?Ignore\b")
PYTHON_SKIP = re.compile(r"@(?:unittest\.)?(?:skip|skipIf|skipUnless|expectedFailure)\b")
DECLARED = re.compile(r"\b(?:fun|void|def|class|object|interface)\s")
TYPE_DECLARATION = re.compile(r"\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:[a-z]+\s+)*(?:class|object|interface)\s")
TOKEN = re.compile(r"\w+|\n|[^\w\s]")
PIN_END = {"\n", "{", "=", "(", ")", ","}
PROSE = "Left to prose:"
UNGUARDED = ADR_DIR / "unguarded.txt"
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

    Anchoring to the line start keeps out a declaration quoted mid-line in a string literal, but one that starts a
    line inside a multi-line string, such as a test fixture, is still read.
    """
    return [
        (match.group(1) or match.group(2), index)
        for index, line in enumerate(text.splitlines())
        if (match := DECLARATION.match(line))
    ]


def indent(line: str) -> int:
    return len(line) - len(line.lstrip())


def annotations(lines: list[str], index: int) -> str:
    """The annotations or decorators of the declaration at [index]: those before it on its line and on the lines
    above, across blank lines and multi-line annotations (a line starting `)` continues up to the `@` line at its
    indentation, every line between indented deeper)."""
    found = [DECLARED.split(lines[index], maxsplit=1)[0]]
    above = index - 1
    while above >= 0:
        line = lines[above].strip()
        if line.startswith(")"):
            start = above - 1
            while start >= 0 and (not lines[start].strip() or indent(lines[start]) > indent(lines[above])):
                start -= 1
            if start < 0 or not lines[start].strip().startswith("@") or indent(lines[start]) != indent(lines[above]):
                break
            found += lines[start:above + 1]
            above = start - 1
        elif not line or line.startswith("@"):
            found.append(line)
            above -= 1
        else:
            break
    return " ".join(found)


def runs(path: pathlib.Path, lines: list[str], index: int, name: str) -> bool:
    """Whether the test declared at [index] runs.

    Kotlin or Java: `@Test` without an `expected` argument and no `@Ignore` on it or on any enclosing type. Python:
    a `test_` method of a class, with no skip or expected-failure decorator on either.
    """
    marks = annotations(lines, index)
    if path.suffix == ".py":
        owner = next((above for above in range(index - 1, -1, -1) if lines[above].strip() and indent(lines[above]) < indent(lines[index])), None)
        return (
            name.startswith("test_")
            and owner is not None
            and lines[owner].lstrip().startswith("class ")
            and PYTHON_SKIP.search(marks + annotations(lines, owner)) is None
        )
    test = TEST_ANNOTATION.search(marks)
    if test is None or re.search(r"\bexpected\s*=", test.group(1) or "") or IGNORE_ANNOTATION.search(marks):
        return False
    depth = indent(lines[index])
    for above in range(index - 1, -1, -1):
        if indent(lines[above]) < depth and TYPE_DECLARATION.match(lines[above]):
            depth = indent(lines[above])
            if IGNORE_ANNOTATION.search(annotations(lines, above)):
                return False
    return True


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
        problems.append(
            f"{record.name}: guard `{symbol}` in `{file.relative_to(root)}` does not run; a guard needs @Test without `expected` and no @Ignore "
            "on it or its class, or a Python test_ method of a class without a skip decorator."
        )
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
        if tags(symbol):
            problems.append(f"{record.name}: guard `{symbol}` is a tagged test name in a file that is not a test source set; name the test's file.")
        elif any(character.isspace() for character in symbol):
            if not any(pinned(symbol, text, file.suffix == ".py") for file, text in zip(files, texts)):
                problems.append(f"{record.name}: `{symbol}` is not in {where}; {SIGNATURE_CHANGED}")
        elif missing_words(symbol, "\n".join(texts)):
            problems.append(f"{record.name}: guard `{symbol}` is not in {where}. Restore it, or point the record at its new name.")
    return problems


def tokens(text: str) -> list[str]:
    """[text]'s words, newlines and punctuation characters, without a trailing `,` before `)`."""
    kept: list[str] = []
    following = ""
    for token in reversed(TOKEN.findall(text)):
        if token != "," or following != ")":
            kept.append(token)
        if token != "\n":
            following = token
    return kept[::-1]


def pinned(pin: str, text: str, python: bool) -> bool:
    """Whether source text [pin] appears in [text] token for token, whatever its line breaks.

    The match must start a line, so an added modifier or annotation fails, and end the file, its line, or before
    `{ = ( ) ,` (and `:` in Python), so an added nullability, type argument, qualifier, return type or supertype
    fails.
    """
    want = [token for token in tokens(pin) if token != "\n"]
    found = tokens(text)
    words = [index for index, token in enumerate(found) if token != "\n"]
    ends = PIN_END | {":"} if python else PIN_END
    for start in range(len(words) - len(want) + 1):
        first = words[start]
        if found[first] != want[0] or (first > 0 and found[first - 1] != "\n"):
            continue
        run = words[start:start + len(want)]
        if [found[index] for index in run] == want and (run[-1] + 1 == len(found) or found[run[-1] + 1] in ends):
            return True
    return False


def guarded_by(record: Record, root: pathlib.Path, moved: dict[str, str], deleted: set[str]) -> tuple[list[str], list[tuple[str, str]]]:
    """The record's "Guarded by" problems, and the (file, test name) pairs it lists as test guards."""
    if not has_guards(record):
        return [], []
    lines = section(record.text, "Guarded by")
    if not lines:
        return [f"{record.name}: \"Guarded by\" lists nothing. Name a guard, or say why the record is left to prose (docs/adr/README.md)."], []
    problems: list[str] = []
    for line in lines:
        bullet = parse_bullet(line)
        if bullet.paths and not bullet.symbols:
            problems.append(f"{record.name}: \"Guarded by\" line `- {line}` names no guard. Name the test, source text or rule after the colon.")
        elif not bullet.paths and not line.startswith(PROSE):
            problems.append(f"{record.name}: \"Guarded by\" line `- {line}` names no backticked path. Name the guard's file, or start the line with \"{PROSE}\".")
        elif not bullet.paths and not line.removeprefix(PROSE).strip():
            problems.append(f"{record.name}: \"{PROSE}\" gives no reason. Say why no machine can check the rest of the record.")
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
            if not tags(name) and MALFORMED_TAG.search(name):
                problems.append(
                    f"{path}: test `{name}` has a malformed ADR tag; end a backticked Kotlin name with (ADR NNNN) "
                    "and any other name with _adr_NNNN."
                )
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


def has_guards(record: Record) -> bool:
    return re.search(r"^## Guarded by\s*$", record.text, re.MULTILINE) is not None


def unguarded_problems(live: list[Record], root: pathlib.Path) -> list[str]:
    """Problems with docs/adr/unguarded.txt, the sorted names of live records that do not list guards yet."""
    path = root / UNGUARDED
    names = path.read_text(encoding="utf-8").split() if path.is_file() else []
    by_name = {pathlib.PurePath(record.name).name: record for record in live}
    problems = [f"{UNGUARDED}: names are not sorted; sort them."] if names != sorted(names) else []
    for name in names:
        if name not in by_name:
            problems.append(f"{UNGUARDED}: `{name}` is not a live record; remove it.")
        elif has_guards(by_name[name]):
            problems.append(f"{by_name[name].name} has a \"Guarded by\" section; remove it from {UNGUARDED}.")
    problems += [
        f"{record.name}: add a \"Guarded by\" section naming a guard, or a `{PROSE}` line saying why none is possible; see docs/adr/README.md."
        for name, record in by_name.items()
        if name not in names and not has_guards(record)
    ]
    return problems


def check(root: pathlib.Path, moved: dict[str, str] | None = None, deleted: set[str] | None = None, test_files: list[str] | None = None) -> list[str]:
    """Every problem in the records under [root], each once; tagged tests are looked for only in [test_files]."""
    every = records(root)
    live = [record for record in every if not record.superseded]
    problems = unguarded_problems(live, root)
    listed: set[tuple[str, str, str]] = set()
    for record in live:
        problems += applies_to_problems(record, root, moved or {}, deleted or set())
        found, guards = guarded_by(record, root, moved or {}, deleted or set())
        problems += found
        listed |= {(record.name, path, name) for path, name in guards}
    return list(dict.fromkeys(problems + untracked_tags(every, listed, test_files or [], root)))


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


def guard_body(text: str, name: str) -> list[str] | None:
    """The stripped, non-blank lines of test [name] in [text], or None when it is not declared exactly once."""
    lines = text.splitlines()
    found = [index for declared, index in declarations(text) if declared == name]
    if len(found) != 1:
        return None
    return [line.strip() for line in test_span(lines, found[0]) if line.strip()]


def guard_edited(old: str, new: str, name: str) -> bool:
    """Whether test [name] exists in both texts and its body differs beyond indentation and blank lines."""
    before, after = guard_body(old, name), guard_body(new, name)
    return before is not None and after is not None and before != after


def git(root: pathlib.Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], cwd=root, capture_output=True, text=True)


def changed_files(root: pathlib.Path, revision: str) -> set[str]:
    """Tracked paths that differ between [revision] and the work tree."""
    return {path for path in git(root, "diff", "--name-only", "-z", revision, "--").stdout.split("\0") if path}


def covered(bullet: Bullet, record: Record, root: pathlib.Path, paths: collections.abc.Iterable[str]) -> list[str]:
    """The [paths] that an "Applies to" bullet names, a directory covering the files under it."""
    places = [place.relative_to(root).as_posix() for place in resolve(bullet, record.name, root, {}, set())[0]]
    return sorted(path for path in paths if any(path == place or path.startswith(place + "/") for place in places))


def governed(record: Record, root: pathlib.Path, changed: set[str]) -> list[str]:
    """The [changed] paths that the record's "Applies to" names."""
    return sorted({path for bullet in bullets(record.text, "Applies to") for path in covered(bullet, record, root, changed)})


def changed_words(root: pathlib.Path, revision: str) -> dict[str, set[str] | None]:
    """Each path changed since [revision]: the identifiers on its added and removed lines, or None when the whole
    file was added or deleted."""
    diff = git(root, "-c", "core.quotePath=false", "diff", "-U0", "--no-renames", "--no-color", revision, "--").stdout
    words: dict[str, set[str] | None] = {}
    path, whole, header = "", False, True
    for line in diff.splitlines():
        if line.startswith("diff --git "):
            path, whole, header = "", False, True
        elif header and line.startswith(("--- ", "+++ ")):
            name = line[4:].rstrip("\t")
            whole = whole or name == "/dev/null"
            path = path if name == "/dev/null" else name[2:]
            if path:
                words[path] = None if whole else words.get(path, set())
        elif line.startswith("@@"):
            header = False
        elif not header and line[:1] in "+-" and (found := words.get(path)) is not None:
            found.update(IDENTIFIER.findall(line[1:]))
    return words


def touches(record: Record, root: pathlib.Path, changed: dict[str, set[str] | None]) -> bool:
    """Whether a changed file falls under one of the record's "Applies to" bullets, and the bullet names no symbol, the
    file was added or deleted whole, or a changed line holds the last identifier of one of its symbols."""
    for bullet in bullets(record.text, "Applies to"):
        names = {found[-1] for symbol in bullet.symbols if (found := IDENTIFIER.findall(symbol))}
        for path in covered(bullet, record, root, changed):
            words = changed[path]
            if words is None or not names or names & words:
                return True
    return False


def governing_notes(root: pathlib.Path, revision: str) -> list[str]:
    """One note naming the live records whose code the change's edited lines touch, unless the change edits them."""
    changed = changed_words(root, revision)
    names = [record.name for record in records(root) if not record.superseded and record.name not in changed and touches(record, root, changed)]
    if not names:
        return []
    more = f" and {len(names) - 8} more" if len(names) > 8 else ""
    return [f"  note: this change edits code governed by {', '.join(names[:8])}{more}; reread them."]


def guard_edit_notes(root: pathlib.Path, revision: str) -> list[str]:
    """A note per guard test edited since [revision] beside code its unchanged record governs."""
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
            if old.returncode == 0 and guard_edited(old.stdout, (root / path).read_text(encoding="utf-8"), name):
                notes.append(
                    f"  note: {record.name}: this change edits guard `{name}` and code the record governs ({code[0]}). "
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
        ["git", "ls-files", "-z", "--", "*/src/*", "scripts/tests/*"],
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
