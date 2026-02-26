#!/usr/bin/env python3
"""Generate sectioned release notes for main prerelease builds."""

from __future__ import annotations

import argparse
import datetime as dt
import re
import subprocess
from collections import OrderedDict
from typing import Iterable

SECTION_ORDER = ["Highlights", "New", "Improvements", "Fixes", "Known Issues"]

PREFIX_CLEANUP_RE = re.compile(r"^[a-z]+(?:\([^)]+\))?!?:\s*", re.IGNORECASE)
CONVENTIONAL_SUBJECT_RE = re.compile(
    r"^\s*"
    r"(?P<type>feat|feature|fix|bug|hotfix|refactor|perf|optimi[sz]e|improve|ui|ux|chore|docs|test|build|ci)"
    r"(?P<scope>\([^)]+\))?"
    r"(?:!)?:\s*"
    r"(?P<text>.+?)\s*$",
    re.IGNORECASE,
)
CONVENTIONAL_BODY_RE = re.compile(
    r"^\s*(?:[-*]\s*)?"
    r"(?P<type>feat|feature|fix|bug|hotfix|refactor|perf|optimi[sz]e|improve|ui|ux|chore|docs|test|build|ci)"
    r"(?P<scope>\([^)]+\))?"
    r"(?:!)?:\s*"
    r"(?P<text>.+?)\s*$",
    re.IGNORECASE,
)

NEW_RE = re.compile(r"^(feat|feature|new|add)(\b|:)", re.IGNORECASE)
FIX_RE = re.compile(r"^(fix|bug|hotfix)(\b|:)", re.IGNORECASE)
IMPROVEMENT_RE = re.compile(r"^(refactor|perf|optimi[sz]e|improve|ui|ux|chore|docs|test|build|ci)(\b|:)", re.IGNORECASE)
SUBJECT_FRAGMENT_SPLIT_RE = re.compile(r"\s+-\s+|\s+\|\s+|\s*;\s*")


def run_git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def strip_list_prefix(text: str) -> str:
    return re.sub(r"^\s*[-*]\s*", "", text).strip()


def normalize_subject(subject: str) -> str:
    cleaned = PREFIX_CLEANUP_RE.sub("", strip_list_prefix(subject))
    return normalize_text(cleaned or subject)


def normalize_text(text: str) -> str:
    return re.sub(r"\s+", " ", text.strip())


def classify_subject(subject: str) -> str:
    lowered = strip_list_prefix(subject).lower()
    if NEW_RE.match(lowered):
        return "New"
    if FIX_RE.match(lowered):
        return "Fixes"
    if IMPROVEMENT_RE.match(lowered):
        return "Improvements"
    return "Highlights"


def classify_type(type_name: str) -> str:
    lowered = type_name.strip().lower()
    if lowered in {"feat", "feature"}:
        return "New"
    if lowered in {"fix", "bug", "hotfix"}:
        return "Fixes"
    return "Improvements"


def normalize_scope(scope: str | None) -> str:
    if not scope:
        return "General"
    raw = scope.strip()
    if raw.startswith("(") and raw.endswith(")"):
        raw = raw[1:-1]
    raw = raw.replace("-", " ").replace("_", " ").strip()
    if not raw:
        return "General"
    return " ".join(part.capitalize() for part in raw.split())


def parse_conventional_line(
    line: str,
    regex: re.Pattern[str],
) -> tuple[str, str, str] | None:
    match = regex.match(line)
    if not match:
        return None
    section = classify_type(match.group("type"))
    scope = normalize_scope(match.group("scope"))
    text = normalize_text(match.group("text") or "")
    if not text:
        return None
    return section, scope, text


def parse_body_entries(body: str) -> list[tuple[str, str, str]]:
    entries: list[tuple[str, str, str]] = []
    for raw_line in body.splitlines():
        parsed = parse_conventional_line(raw_line, CONVENTIONAL_BODY_RE)
        if not parsed:
            continue
        entries.append(parsed)
    return entries


def parse_conventional_subject_fragments(subject: str) -> list[tuple[str, str, str]]:
    fragments = SUBJECT_FRAGMENT_SPLIT_RE.split(subject.strip())
    parsed_entries: list[tuple[str, str, str]] = []
    for fragment in fragments:
        candidate = strip_list_prefix(fragment)
        if not candidate:
            continue
        parsed = parse_conventional_line(candidate, CONVENTIONAL_SUBJECT_RE)
        if not parsed:
            return []
        parsed_entries.append(parsed)
    return parsed_entries


def dedupe(entries: Iterable[tuple[str, str, str]]) -> list[tuple[str, str, str]]:
    seen: set[tuple[str, str, str]] = set()
    result: list[tuple[str, str, str]] = []
    for entry in entries:
        if entry in seen:
            continue
        seen.add(entry)
        result.append(entry)
    return result


def collect_commit_entries(previous_tag: str | None, head: str) -> list[tuple[str, str, str]]:
    if previous_tag:
        raw = run_git("log", "--pretty=format:%s%x1f%b%x1e", f"{previous_tag}..{head}")
    else:
        raw = run_git("log", "--pretty=format:%s%x1f%b%x1e", "-n", "40", head)

    entries: list[tuple[str, str, str]] = []
    for record in raw.split("\x1e"):
        payload = record.strip()
        if not payload:
            continue
        if "\x1f" in payload:
            subject, body = payload.split("\x1f", 1)
        else:
            subject, body = payload, ""
        subject = subject.strip()
        body_entries = parse_body_entries(body)
        if body_entries:
            entries.extend(body_entries)
            continue
        if subject:
            parsed_subjects = parse_conventional_subject_fragments(subject)
            if parsed_subjects:
                entries.extend(parsed_subjects)
            else:
                section = classify_subject(subject)
                entries.append((section, "General", normalize_subject(subject)))
    return dedupe(entries)


def build_sections(
    entries: list[tuple[str, str, str]],
) -> OrderedDict[str, OrderedDict[str, list[str]]]:
    sections: OrderedDict[str, OrderedDict[str, list[str]]] = OrderedDict(
        (name, OrderedDict()) for name in SECTION_ORDER
    )

    for section, scope, text in entries:
        section_groups = sections[section]
        section_groups.setdefault(scope, []).append(text)

    if not entries:
        sections["Highlights"].setdefault("General", []).append(
            "No commit metadata available for this build."
        )

    return sections


def render_markdown(
    sections: OrderedDict[str, OrderedDict[str, list[str]]],
    channel: str,
    short_sha: str,
    previous_tag: str | None,
) -> str:
    lines: list[str] = []
    lines.append(f"Channel: {channel}")
    lines.append(f"Commit: {short_sha}")
    if previous_tag:
        lines.append(f"Range: {previous_tag}..HEAD")
    lines.append(f"Generated: {dt.datetime.now(dt.timezone.utc).isoformat()}")
    lines.append("")

    for section_name in SECTION_ORDER:
        scope_groups = sections[section_name]
        if not scope_groups:
            continue
        lines.append(f"## {section_name}")
        for scope, bullets in scope_groups.items():
            if scope == "General":
                for bullet in bullets:
                    lines.append(f"- {bullet}")
                continue
            lines.append(f"- {scope}:")
            for bullet in bullets:
                lines.append(f"  - {bullet}")
        lines.append("")

    return "\n".join(lines).rstrip() + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, help="Path to output markdown file")
    parser.add_argument("--channel", default="main", help="Release channel name")
    parser.add_argument("--head", default="HEAD", help="Git rev to use as head")
    parser.add_argument("--commit", default="", help="Short commit SHA for metadata")
    parser.add_argument("--previous-tag", default="", help="Previous release tag for range")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    previous_tag = args.previous_tag.strip() or None
    short_sha = args.commit.strip() or run_git("rev-parse", "--short", args.head)

    entries = collect_commit_entries(previous_tag=previous_tag, head=args.head)
    sections = build_sections(entries)
    markdown = render_markdown(
        sections=sections,
        channel=args.channel,
        short_sha=short_sha,
        previous_tag=previous_tag,
    )

    with open(args.output, "w", encoding="utf-8") as fh:
        fh.write(markdown)


if __name__ == "__main__":
    main()
