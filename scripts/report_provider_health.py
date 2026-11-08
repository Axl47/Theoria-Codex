#!/usr/bin/env python3
"""Turns provider-health JSON reports into one GitHub issue per degraded provider.

The repository is public, so issues, comments, and step summaries are built only from allowlisted
values: a strict source-id pattern, known status/reason enum names, and check names with numeric
segments removed. Nothing else from the report (URLs, messages, ids, tags, counts, latencies) is
ever rendered.

Subcommands:
  retry-list <first.json>
      Prints the sources that are FAILED/DEGRADED, comma separated, for -Ptheoria.liveSources.sources.
  reconcile --first <json> [--confirm <json>] --run-url <url> [--dry-run]
      Opens, updates, or closes issues. A provider is unhealthy only if it is unhealthy in both
      reports, so a single transient blip never opens an issue.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
from dataclasses import dataclass
from typing import Any, Iterable

LABEL = "provider-health"
TITLE_PREFIX = "Provider health: "
SOURCE_ID = re.compile(r"^[A-Z][A-Z0-9_]{1,31}$")
CHECK_NAME = re.compile(r"^[a-z0-9-]+$")
FINGERPRINT = re.compile(r"<!-- provider-health-fingerprint: ([0-9a-f]{16}) -->")
RUN_URL = re.compile(r"^https?://[A-Za-z0-9./_-]+$")
STATUSES = frozenset({"OK", "DEGRADED", "FAILED", "SKIPPED"})
UNHEALTHY = frozenset({"DEGRADED", "FAILED"})
REASONS = frozenset(
    {"AUTH_REQUIRED", "AUTH_EXPIRED", "RATE_LIMITED", "NETWORK", "PARSE", "UNKNOWN"}
)
UNCLASSIFIED = "UNCLASSIFIED"


class ReportError(Exception):
    """The report cannot be trusted; the workflow must fail visibly."""


@dataclass(frozen=True)
class Step:
    check: str
    status: str
    reason: str


@dataclass(frozen=True)
class SourceHealth:
    status: str
    reason: str
    steps: tuple[Step, ...]  # non-OK steps only, sorted


@dataclass(frozen=True)
class Action:
    kind: str  # create | update | resolve
    source: str
    number: int | None = None
    body: str = ""
    comment: str = ""

    @property
    def title(self) -> str:
        return TITLE_PREFIX + self.source


# --- sanitising -------------------------------------------------------------------------------


def clean_check_name(raw: Any) -> str:
    if not isinstance(raw, str):
        return "unnamed-check"
    segments = [segment for segment in raw.split("-") if not segment.isdigit()]
    name = "-".join(segments)
    return name if CHECK_NAME.fullmatch(name) else "unnamed-check"


def clean_status(raw: Any) -> str:
    return raw if isinstance(raw, str) and raw in STATUSES else UNCLASSIFIED


def clean_reason(raw: Any) -> str:
    return raw if isinstance(raw, str) and raw in REASONS else UNCLASSIFIED


def clean_source(raw: Any) -> str | None:
    return raw if isinstance(raw, str) and SOURCE_ID.fullmatch(raw) else None


# --- report parsing ---------------------------------------------------------------------------


def load_report(path: str) -> dict[str, Any]:
    try:
        report = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise ReportError("report is missing or not valid JSON") from None
    if not isinstance(report, dict):
        raise ReportError("report is not a JSON object")
    if report.get("liveProvidersEnabled") is not True:
        raise ReportError("report was produced with live providers disabled")
    results = report.get("results")
    if not isinstance(results, list) or not results:
        raise ReportError("report has no results")
    return report


def _entries(report: dict[str, Any], key: str) -> Iterable[dict[str, Any]]:
    value = report.get(key)
    return (entry for entry in value if isinstance(entry, dict)) if isinstance(value, list) else ()


def source_health(report: dict[str, Any]) -> dict[str, SourceHealth]:
    """Maps each valid source id to its sanitised aggregate status and non-OK steps."""
    steps: dict[str, set[Step]] = {}
    for entry in _entries(report, "probeResults"):
        source = clean_source(entry.get("source"))
        status = clean_status(entry.get("status"))
        if source and status != "OK":
            steps.setdefault(source, set()).add(
                Step(clean_check_name(entry.get("checkName")), status, clean_reason(entry.get("failureReason")))
            )
    health: dict[str, SourceHealth] = {}
    for entry in _entries(report, "results"):
        source = clean_source(entry.get("source"))
        if not source:
            continue
        status = clean_status(entry.get("status"))
        reason = clean_reason(entry.get("failureReason"))
        source_steps = steps.get(source) or (
            {Step("source-probe", status, reason)} if status not in ("OK", "SKIPPED") else set()
        )
        health[source] = SourceHealth(status, reason, tuple(sorted(source_steps, key=_step_key)))
    return health


def _step_key(step: Step) -> tuple[str, str, str]:
    return (step.check, step.status, step.reason)


def fingerprint(health: SourceHealth) -> str:
    canonical = "\n".join("|".join(_step_key(step)) for step in health.steps)
    return hashlib.sha256(canonical.encode()).hexdigest()[:16]


# --- decisions (pure) -------------------------------------------------------------------------


def retry_sources(first: dict[str, SourceHealth]) -> list[str]:
    return sorted(source for source, health in first.items() if health.status in UNHEALTHY)


def classify(
    first: dict[str, SourceHealth], confirm: dict[str, SourceHealth]
) -> tuple[dict[str, SourceHealth], set[str]]:
    """Returns (unhealthy sources with their latest observation, healthy sources)."""
    unhealthy: dict[str, SourceHealth] = {}
    healthy: set[str] = set()
    for source, initial in first.items():
        latest = confirm.get(source, initial)
        if latest.status == "OK":
            healthy.add(source)
        elif initial.status in UNHEALTHY and source in confirm and latest.status in UNHEALTHY:
            unhealthy[source] = latest
        # SKIPPED, unconfirmed, or unclassified: no evidence either way.
    return unhealthy, healthy


def render_body(source: str, health: SourceHealth, run_url: str, today: str) -> str:
    rows = "\n".join(f"| `{s.check}` | {s.status} | {s.reason} |" for s in health.steps)
    link = run_url if RUN_URL.fullmatch(run_url) else "(unavailable)"
    return (
        f"The daily provider health check found `{source}` degraded ({health.status}) on {today}.\n\n"
        "| Check | Status | Reason |\n| --- | --- | --- |\n"
        f"{rows}\n\n"
        f"Run: {link}\n\n"
        "Re-run locally: `./gradlew :core-sources:providerHealthCheck -Ptheoria.liveProviders=true "
        f"-Ptheoria.liveSources.sources={source}`\n\n"
        f"<!-- provider-health-fingerprint: {fingerprint(health)} -->\n"
    )


def plan(
    first: dict[str, SourceHealth],
    confirm: dict[str, SourceHealth],
    open_issues: list[dict[str, Any]],
    run_url: str,
    today: str,
) -> list[Action]:
    existing: dict[str, dict[str, Any]] = {}
    for issue in sorted(open_issues, key=lambda i: i.get("number", 0)):
        existing.setdefault(str(issue.get("title")), issue)
    unhealthy, healthy = classify(first, confirm)
    actions: list[Action] = []
    for source in sorted(unhealthy):
        health = unhealthy[source]
        body = render_body(source, health, run_url, today)
        issue = existing.get(TITLE_PREFIX + source)
        if issue is None:
            actions.append(Action("create", source, body=body))
            continue
        match = FINGERPRINT.search(str(issue.get("body") or ""))
        if match and match.group(1) == fingerprint(health):
            continue
        actions.append(
            Action(
                "update", source, issue["number"], body,
                comment=f"Failing checks changed on {today}; the issue body shows the current state. Run: "
                f"{run_url if RUN_URL.fullmatch(run_url) else '(unavailable)'}",
            )
        )
    for source in sorted(healthy):
        issue = existing.get(TITLE_PREFIX + source)
        if issue is not None:
            actions.append(
                Action("resolve", source, issue["number"], comment=f"`{source}` recovered as of {today}; closing.")
            )
    return actions


def render_summary(first: dict[str, SourceHealth], confirm: dict[str, SourceHealth]) -> str:
    rows = []
    for source in sorted(first):
        latest = confirm.get(source, first[source])
        reason = latest.reason if latest.status in UNHEALTHY else "-"
        rows.append(f"| {source} | {latest.status} | {reason} |")
    return "### Provider health\n\n| Source | Status | Reason |\n| --- | --- | --- |\n" + "\n".join(rows) + "\n"


# --- gh layer ---------------------------------------------------------------------------------


def gh(*args: str) -> str:
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def list_open_issues() -> list[dict[str, Any]]:
    # Only triagers can apply labels, so the label filter keeps lookalike public issues out.
    return json.loads(
        gh("issue", "list", "--label", LABEL, "--state", "open", "--limit", "200", "--json", "number,title,body")
    )


def apply(actions: list[Action]) -> None:
    if not actions:
        return
    gh("label", "create", LABEL, "--force", "--description", "Daily provider health monitor",
       "--color", "d93f0b")
    for action in actions:
        if action.kind == "create":
            gh("issue", "create", "--title", action.title, "--label", LABEL, "--body", action.body)
        elif action.kind == "update":
            gh("issue", "edit", str(action.number), "--body", action.body)
            gh("issue", "comment", str(action.number), "--body", action.comment)
        else:
            gh("issue", "comment", str(action.number), "--body", action.comment)
            gh("issue", "close", str(action.number))


# --- CLI --------------------------------------------------------------------------------------


def run_retry_list(args: argparse.Namespace) -> int:
    try:
        first = source_health(load_report(args.first))
    except ReportError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(",".join(retry_sources(first)))
    return 0


def run_reconcile(args: argparse.Namespace) -> int:
    try:
        first = source_health(load_report(args.first))
        confirm = source_health(load_report(args.confirm)) if args.confirm else {}
    except ReportError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(render_summary(first, confirm))
    today = datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    actions = plan(first, confirm, list_open_issues(), args.run_url, today)
    for action in actions:
        print(f"{'would ' if args.dry_run else ''}{action.kind}: {action.title}")
    if not actions:
        print("no issue changes")
    if not args.dry_run:
        apply(actions)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    retry = commands.add_parser("retry-list")
    retry.add_argument("first")
    retry.set_defaults(handler=run_retry_list)
    reconcile = commands.add_parser("reconcile")
    reconcile.add_argument("--first", required=True)
    reconcile.add_argument("--confirm")
    reconcile.add_argument("--run-url", required=True)
    reconcile.add_argument("--dry-run", action="store_true")
    reconcile.set_defaults(handler=run_reconcile)
    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    sys.exit(main())
