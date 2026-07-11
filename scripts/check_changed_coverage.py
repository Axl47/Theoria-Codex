#!/usr/bin/env python3
"""Enforce Kover/JaCoCo line coverage only on changed production Kotlin lines."""

from __future__ import annotations

import argparse
import ast
import collections
import dataclasses
import os
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
from collections.abc import Iterable, Mapping, Sequence


DEFAULT_MINIMUM = 60.0
MODULE_NAME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")
SourceKey = tuple[str, str]
CoverageMap = dict[SourceKey, dict[int, bool]]
ChangedLines = dict[pathlib.PurePosixPath, set[int]]

HUNK_PATTERN = re.compile(
    r"^@@ -\d+(?:,\d+)? \+(?P<start>\d+)(?:,(?P<count>\d+))? @@"
)
PACKAGE_PATTERN = re.compile(
    r"^\s*package\s+([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*(?:;|$)",
    flags=re.MULTILINE,
)
TRAVERSAL_EXCLUSIONS = {
    ".git",
    ".gradle",
    ".kotlin",
    "build",
    "generated",
    "node_modules",
}


class CoverageCheckError(RuntimeError):
    """Raised when coverage inputs cannot be interpreted safely."""


@dataclasses.dataclass(frozen=True)
class CoverageResult:
    changed_production_lines: int
    executable_lines: int
    covered_lines: int
    uncovered_locations: tuple[tuple[pathlib.PurePosixPath, int], ...]

    @property
    def ignored_lines(self) -> int:
        return self.changed_production_lines - self.executable_lines

    @property
    def percentage(self) -> float:
        if self.executable_lines == 0:
            return 100.0
        return 100.0 * self.covered_lines / self.executable_lines


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _normalize_package(raw_package: str) -> str:
    return raw_package.strip().strip("/").replace(".", "/")


def parse_coverage_xml(xml_path: pathlib.Path) -> CoverageMap:
    """Parse the source-line section of a Kover or JaCoCo XML report."""
    try:
        root = ElementTree.parse(xml_path).getroot()
    except (ElementTree.ParseError, OSError) as error:
        raise CoverageCheckError(f"could not parse coverage XML {xml_path}: {error}") from error

    coverage: CoverageMap = {}
    for package in root.iter():
        if _local_name(package.tag) != "package":
            continue
        package_name = _normalize_package(package.attrib.get("name", ""))
        for source_file in package:
            if _local_name(source_file.tag) != "sourcefile":
                continue
            raw_source_name = source_file.attrib.get("name", "")
            source_name = pathlib.PurePosixPath(raw_source_name.replace("\\", "/")).name
            if not source_name:
                raise CoverageCheckError(
                    f"coverage XML {xml_path} contains a sourcefile without a name"
                )
            key = (package_name, source_name)
            if key in coverage:
                raise CoverageCheckError(
                    "coverage XML cannot distinguish duplicate source identity "
                    f"{package_name}/{source_name}"
                )

            lines: dict[int, bool] = {}
            for line in source_file:
                if _local_name(line.tag) != "line":
                    continue
                try:
                    line_number = int(line.attrib["nr"])
                    covered_instructions = int(line.attrib.get("ci", "0"))
                except (KeyError, ValueError) as error:
                    raise CoverageCheckError(
                        f"coverage XML {xml_path} contains an invalid line entry"
                    ) from error
                if line_number <= 0 or covered_instructions < 0:
                    raise CoverageCheckError(
                        f"coverage XML {xml_path} contains an invalid line entry"
                    )
                lines[line_number] = covered_instructions > 0
            coverage[key] = lines
    return coverage


def _decode_git_header_path(raw_path: str) -> pathlib.PurePosixPath | None:
    value = raw_path.rstrip()
    if value == "/dev/null":
        return None
    if value.startswith('"'):
        try:
            value = ast.literal_eval(value)
        except (SyntaxError, ValueError) as error:
            raise CoverageCheckError(f"could not decode Git path {raw_path!r}") from error
    else:
        value = value.split("\t", 1)[0]
    if value.startswith("b/"):
        value = value[2:]
    path = pathlib.PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts:
        raise CoverageCheckError(f"Git produced an unsafe repository path: {value!r}")
    return path


def parse_git_diff(diff_text: str) -> ChangedLines:
    """Return new-side line numbers from a zero-context unified Git diff."""
    changed: ChangedLines = collections.defaultdict(set)
    current_path: pathlib.PurePosixPath | None = None
    for raw_line in diff_text.splitlines():
        if raw_line.startswith("+++ "):
            current_path = _decode_git_header_path(raw_line[4:])
            continue
        match = HUNK_PATTERN.match(raw_line)
        if match is None or current_path is None:
            continue
        start = int(match.group("start"))
        count = int(match.group("count") or "1")
        if count > 0:
            changed[current_path].update(range(start, start + count))
    return dict(changed)


def is_production_kotlin_path(path: pathlib.PurePosixPath) -> bool:
    if path.suffix.lower() != ".kt":
        return False
    lowered = tuple(part.lower() for part in path.parts)
    if "baseline-profile" in lowered:
        return False
    if any(part in {"build", "generated"} for part in lowered):
        return False
    return any(
        lowered[index] == "src"
        and index + 1 < len(lowered)
        and lowered[index + 1] == "main"
        for index in range(len(lowered))
    )


def production_changed_lines(
    changed: Mapping[pathlib.PurePosixPath, set[int]],
    included_modules: frozenset[str] | None = None,
) -> ChangedLines:
    return {
        path: set(lines)
        for path, lines in changed.items()
        if lines
        and is_production_kotlin_path(path)
        and (
            included_modules is None
            or (path.parts and path.parts[0] in included_modules)
        )
    }


def _layout_package(path: pathlib.PurePosixPath) -> str:
    parts = path.parts
    for index in range(len(parts) - 1):
        if parts[index].lower() == "src" and parts[index + 1].lower() == "main":
            package_parts = list(parts[index + 2 : -1])
            if package_parts and package_parts[0].lower() in {"java", "kotlin"}:
                package_parts.pop(0)
            return "/".join(package_parts)
    return ""


def source_key_for_path(
    repo: pathlib.Path,
    relative_path: pathlib.PurePosixPath,
) -> SourceKey:
    source_path = repo.joinpath(*relative_path.parts)
    try:
        source_text = source_path.read_text(encoding="utf-8")
    except OSError as error:
        raise CoverageCheckError(f"could not read changed source {relative_path}: {error}") from error
    package_match = PACKAGE_PATTERN.search(source_text)
    package_name = (
        package_match.group(1).replace(".", "/")
        if package_match is not None
        else _layout_package(relative_path)
    )
    return package_name, relative_path.name


def iter_production_kotlin_paths(repo: pathlib.Path) -> Iterable[pathlib.PurePosixPath]:
    for current_root, directory_names, file_names in os.walk(repo):
        current_path = pathlib.Path(current_root)
        relative_root = current_path.relative_to(repo)
        lowered_root = {part.lower() for part in relative_root.parts}
        if "baseline-profile" in lowered_root:
            directory_names[:] = []
            continue
        directory_names[:] = [
            name
            for name in directory_names
            if name.lower() not in TRAVERSAL_EXCLUSIONS
        ]
        for file_name in file_names:
            if not file_name.lower().endswith(".kt"):
                continue
            relative_path = pathlib.PurePosixPath(*(relative_root.parts + (file_name,)))
            if is_production_kotlin_path(relative_path):
                yield relative_path


def build_source_index(
    repo: pathlib.Path,
) -> dict[SourceKey, tuple[pathlib.PurePosixPath, ...]]:
    mutable_index: dict[SourceKey, list[pathlib.PurePosixPath]] = collections.defaultdict(list)
    for relative_path in iter_production_kotlin_paths(repo):
        mutable_index[source_key_for_path(repo, relative_path)].append(relative_path)
    return {
        key: tuple(sorted(paths, key=lambda path: path.as_posix()))
        for key, paths in mutable_index.items()
    }


def calculate_changed_coverage(
    repo: pathlib.Path,
    changed: Mapping[pathlib.PurePosixPath, set[int]],
    report: Mapping[SourceKey, Mapping[int, bool]],
    included_modules: frozenset[str] | None = None,
) -> CoverageResult:
    production_changes = production_changed_lines(changed, included_modules)
    source_index = build_source_index(repo)
    executable_locations: list[tuple[pathlib.PurePosixPath, int]] = []
    uncovered_locations: list[tuple[pathlib.PurePosixPath, int]] = []
    missing_report_sources: list[pathlib.PurePosixPath] = []

    for relative_path in sorted(production_changes, key=lambda path: path.as_posix()):
        source_key = source_key_for_path(repo, relative_path)
        report_lines = report.get(source_key)
        if report_lines is None:
            missing_report_sources.append(relative_path)
            continue
        candidates = source_index.get(source_key, ())
        if len(candidates) > 1:
            candidate_list = ", ".join(path.as_posix() for path in candidates)
            raise CoverageCheckError(
                "coverage source identity is ambiguous across modules: "
                f"{source_key[0]}/{source_key[1]} maps to {candidate_list}"
            )
        for line_number in sorted(production_changes[relative_path]):
            covered = report_lines.get(line_number)
            if covered is None:
                continue
            location = (relative_path, line_number)
            executable_locations.append(location)
            if not covered:
                uncovered_locations.append(location)

    if missing_report_sources:
        missing_list = ", ".join(path.as_posix() for path in missing_report_sources)
        raise CoverageCheckError(
            "coverage XML has no source entry for eligible changed production files: "
            f"{missing_list}"
        )

    changed_line_count = sum(len(lines) for lines in production_changes.values())
    executable_count = len(executable_locations)
    return CoverageResult(
        changed_production_lines=changed_line_count,
        executable_lines=executable_count,
        covered_lines=executable_count - len(uncovered_locations),
        uncovered_locations=tuple(uncovered_locations),
    )


def git_diff_text(repo: pathlib.Path, base: str) -> str:
    command = [
        "git",
        "-c",
        "core.quotePath=false",
        "diff",
        "--unified=0",
        "--no-color",
        f"{base}...HEAD",
        "--",
        "*.kt",
    ]
    completed = subprocess.run(
        command,
        cwd=repo,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or "Git diff failed without an error message"
        raise CoverageCheckError(f"could not diff {base}...HEAD: {detail}")
    return completed.stdout


def _print_uncovered(result: CoverageResult) -> None:
    if not result.uncovered_locations:
        return
    print("Uncovered executable changed lines:", file=sys.stderr)
    display_limit = 50
    for path, line_number in result.uncovered_locations[:display_limit]:
        print(f"  - {path}:{line_number}", file=sys.stderr)
    remaining = len(result.uncovered_locations) - display_limit
    if remaining > 0:
        print(f"  - ... and {remaining} more", file=sys.stderr)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Check Kover/JaCoCo coverage for executable changed production Kotlin lines."
    )
    parser.add_argument("--xml", required=True, help="Kover/JaCoCo XML report path.")
    parser.add_argument("--base", default=None, help="Base revision to diff against HEAD.")
    parser.add_argument(
        "--minimum",
        type=float,
        default=DEFAULT_MINIMUM,
        help=f"Minimum changed-line percentage (default: {DEFAULT_MINIMUM:g}).",
    )
    parser.add_argument(
        "--repo",
        default=".",
        help="Repository root used for Git and source mapping (default: current directory).",
    )
    parser.add_argument(
        "--include-module",
        action="append",
        default=None,
        help=(
            "Top-level module eligible for the coverage gate. Repeat for multiple modules; "
            "when omitted, all production Kotlin modules are eligible."
        ),
    )
    args = parser.parse_args(argv)
    if not 0.0 <= args.minimum <= 100.0:
        parser.error("--minimum must be between 0 and 100")
    if args.include_module:
        invalid_modules = [
            module
            for module in args.include_module
            if not MODULE_NAME_PATTERN.fullmatch(module)
        ]
        if invalid_modules:
            parser.error(
                "--include-module must name a top-level repository module: "
                + ", ".join(invalid_modules)
            )
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    base = (args.base or "").strip()
    if not base:
        print("Changed-line coverage skipped: no base revision was supplied.")
        return 0

    repo = pathlib.Path(args.repo).resolve()
    included_modules = (
        frozenset(args.include_module)
        if args.include_module
        else None
    )
    xml_path = pathlib.Path(args.xml)
    if not xml_path.is_absolute():
        xml_path = repo / xml_path

    try:
        if not repo.is_dir():
            raise CoverageCheckError(f"repository root does not exist: {repo}")
        if not xml_path.is_file():
            raise CoverageCheckError(
                f"coverage XML is missing for base {base}: {xml_path}"
            )
        report = parse_coverage_xml(xml_path)
        changed = parse_git_diff(git_diff_text(repo, base))
        result = calculate_changed_coverage(
            repo,
            changed,
            report,
            included_modules=included_modules,
        )
    except CoverageCheckError as error:
        print(f"Changed-line coverage check failed: {error}", file=sys.stderr)
        return 2

    if result.executable_lines == 0:
        scope = (
            f" in {', '.join(sorted(included_modules))}"
            if included_modules is not None
            else ""
        )
        print(
            "Changed-line coverage skipped: no executable changed production Kotlin lines"
            f"{scope} "
            f"were present in the XML report ({result.changed_production_lines} changed "
            "production lines inspected)."
        )
        return 0

    summary = (
        f"Changed-line coverage: {result.covered_lines}/{result.executable_lines} executable "
        f"changed lines covered ({result.percentage:.2f}%; minimum {args.minimum:.2f}%)."
    )
    if included_modules is not None:
        summary += f" Eligible modules: {', '.join(sorted(included_modules))}."
    if result.ignored_lines:
        summary += (
            f" {result.ignored_lines} changed production lines had no executable XML entry "
            "and were excluded."
        )

    if result.percentage + 1e-9 < args.minimum:
        print(f"Changed-line coverage check failed: {summary}", file=sys.stderr)
        _print_uncovered(result)
        return 1

    print(summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
