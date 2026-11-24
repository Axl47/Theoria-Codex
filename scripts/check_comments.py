#!/usr/bin/env python3
"""Comment ban: tracked Kotlin and Java sources carry no comments; decisions live in docs/adr/.

Any comment fails, KDoc and TODOs included, except the directives a tool or a licence requires
(ALLOWED_DIRECTIVE).
"""

from __future__ import annotations

import pathlib
import re
import subprocess
import sys
from dataclasses import dataclass

# App and test sources. Gradle build scripts (.kts) are configuration, not app code, and stay out of scope.
SOURCE_SUFFIXES = (".kt", ".java")

# TODO and FIXME comments are banned like any other: open an issue instead.
# Comments a tool or a licence requires, so they are allowed anywhere:
# - `noinspection`: Android lint's only in-source suppression for a single statement.
# - `@formatter:off` / `@formatter:on`: IDE formatter fences around hand-aligned code.
# - `// Derived from Mihon|Aniyomi (<url>), Apache License 2.0; ...`: the notice Apache 2.0 requires on
#   extension-host files adapted from those projects, matched in full.
# Detekt suppressions are `@Suppress` annotations, not comments. Generated code lives under build/, which
# git does not track, so it needs no exception.
ALLOWED_DIRECTIVE = re.compile(
    r"//\s*noinspection\s+\w+(\s*,\s*\w+)*"
    r"|// Derived from (Mihon|Aniyomi) \(https://github\.com/\w+/\w+\), Apache License 2\.0;"
    r" see LICENSE-APACHE-2\.0\.txt in this module\."
    r"|//\s*@formatter:(on|off)"
    r"|/\*\s*@formatter:(on|off)\s*\*/"
)


@dataclass(frozen=True)
class Comment:
    line: int
    text: str


class _Lexer:
    """Finds comments in Kotlin or Java source, skipping string, template and character literals."""

    def __init__(self, source: str, kotlin: bool) -> None:
        self.source = source
        self.kotlin = kotlin
        self.index = 0
        self.comments: list[Comment] = []

    def line(self, index: int) -> int:
        return self.source.count("\n", 0, index) + 1

    def peek(self, text: str) -> bool:
        return self.source.startswith(text, self.index)

    def code(self, until_brace: bool = False) -> None:
        depth = 0
        source = self.source
        while self.index < len(source):
            char = source[self.index]
            if self.peek("//"):
                end = source.find("\n", self.index)
                end = len(source) if end == -1 else end
                self.comments.append(Comment(self.line(self.index), source[self.index:end]))
                self.index = end
            elif self.peek("/*"):
                self.block_comment()
            elif self.peek('"""'):
                self.index += 3
                self.string(raw=True)
            elif char == '"':
                self.index += 1
                self.string(raw=False)
            elif char == "'":
                self.char_literal()
            elif self.kotlin and char == "`":
                end = source.find("`", self.index + 1)
                self.index = len(source) if end == -1 else end + 1
            elif until_brace and char == "{":
                depth += 1
                self.index += 1
            elif until_brace and char == "}":
                self.index += 1
                if depth == 0:
                    return
                depth -= 1
            else:
                self.index += 1

    def block_comment(self) -> None:
        start = self.index
        self.index += 2
        depth = 1
        while self.index < len(self.source) and depth:
            if self.kotlin and self.peek("/*"):
                depth += 1
                self.index += 2
            elif self.peek("*/"):
                depth -= 1
                self.index += 2
            else:
                self.index += 1
        self.comments.append(Comment(self.line(start), self.source[start:self.index]))

    def string(self, raw: bool) -> None:
        source = self.source
        escapes = not raw or not self.kotlin
        while self.index < len(source):
            if escapes and source[self.index] == "\\":
                self.index += 2
                continue
            if raw and self.peek('"""'):
                self.index += 3
                while self.peek('"'):
                    self.index += 1
                return
            char = source[self.index]
            if not raw and char == '"':
                self.index += 1
                return
            if self.kotlin and self.peek("${"):
                self.index += 2
                self.code(until_brace=True)
            else:
                self.index += 1

    def char_literal(self) -> None:
        self.index += 1
        if self.peek("\\"):
            self.index += 2
        end = self.source.find("'", self.index)
        self.index = len(self.source) if end == -1 else end + 1


def find_comments(source: str, kotlin: bool = True) -> list[Comment]:
    """Every comment in the source, in order; each `//` line and each block comment counts once."""
    lexer = _Lexer(source, kotlin)
    lexer.code()
    return lexer.comments


def banned_comments(path: pathlib.Path) -> list[Comment]:
    comments = find_comments(path.read_text(encoding="utf-8"), kotlin=path.suffix == ".kt")
    return [comment for comment in comments if not ALLOWED_DIRECTIVE.fullmatch(comment.text.strip())]


def source_files() -> list[pathlib.Path]:
    listed = subprocess.run(
        ["git", "ls-files", "-z", "--", *(f"*{suffix}" for suffix in SOURCE_SUFFIXES)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return [pathlib.Path(name) for name in listed.split("\0") if name and pathlib.Path(name).is_file()]


def check(paths: list[pathlib.Path]) -> list[str]:
    """One problem line per banned comment in [paths]."""
    return [
        f"{path.as_posix()}:{comment.line}: {comment.text.splitlines()[0]}"
        for path in paths
        for comment in banned_comments(path)
    ]


def main() -> int:
    problems = check(source_files())
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(
            "Code comments are banned: delete them, and record any decision they explain in docs/adr/.",
            file=sys.stderr,
        )
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
