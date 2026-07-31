#!/usr/bin/env python3
"""Fail-closed line-budget and Detekt-baseline debt ratchet."""

from __future__ import annotations

import argparse
import collections
import json
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
from typing import Any


EXPECTED_ROOT_KEYS = {"version", "sourceModules", "lineBudgets", "detektBaselines"}
EXPECTED_LINE_KEYS = {"production", "test", "productionExceptions"}


class ConfigurationError(ValueError):
    """The quality budget is absent, malformed, or internally inconsistent."""


def read_json(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ConfigurationError(f"cannot read valid JSON config {path}: {error}") from error
    if not isinstance(value, dict):
        raise ConfigurationError("quality config root must be an object")
    return value


def require_exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        raise ConfigurationError(
            f"{label} keys must be exactly {sorted(expected)}; found {sorted(value)}"
        )


def positive_int(value: Any, label: str, *, allow_zero: bool = False) -> int:
    minimum = 0 if allow_zero else 1
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ConfigurationError(f"{label} must be an integer >= {minimum}")
    return value


def validate_config(config: dict[str, Any]) -> None:
    require_exact_keys(config, EXPECTED_ROOT_KEYS, "root")
    if config["version"] != 1:
        raise ConfigurationError("unsupported quality config version")
    modules = config["sourceModules"]
    if (
        not isinstance(modules, list)
        or not modules
        or any(not isinstance(module, str) or not module for module in modules)
        or len(modules) != len(set(modules))
    ):
        raise ConfigurationError("sourceModules must be a non-empty unique string list")
    line_budgets = config["lineBudgets"]
    if not isinstance(line_budgets, dict):
        raise ConfigurationError("lineBudgets must be an object")
    require_exact_keys(line_budgets, EXPECTED_LINE_KEYS, "lineBudgets")
    positive_int(line_budgets["production"], "production line budget")
    positive_int(line_budgets["test"], "test line budget")
    exceptions = line_budgets["productionExceptions"]
    if not isinstance(exceptions, dict):
        raise ConfigurationError("productionExceptions must be an object")
    for path, budget in exceptions.items():
        if not isinstance(path, str) or not path.endswith(".kt"):
            raise ConfigurationError("production exception paths must identify Kotlin files")
        positive_int(budget, f"production exception {path}")
    baselines = config["detektBaselines"]
    if not isinstance(baselines, dict) or not baselines:
        raise ConfigurationError("detektBaselines must be a non-empty object")
    for path, counts in baselines.items():
        if not isinstance(path, str) or not path.endswith(".xml") or not isinstance(counts, dict):
            raise ConfigurationError("each Detekt baseline must map an XML path to rule counts")
        for rule, count in counts.items():
            if not isinstance(rule, str) or not rule:
                raise ConfigurationError(f"invalid Detekt rule name for {path}")
            positive_int(count, f"Detekt count {path}:{rule}", allow_zero=True)


def physical_line_count(path: pathlib.Path) -> int:
    try:
        return len(path.read_text(encoding="utf-8").splitlines())
    except (OSError, UnicodeError) as error:
        raise ConfigurationError(f"cannot read Kotlin source {path}: {error}") from error


def discover_kotlin_modules(root: pathlib.Path) -> set[str]:
    modules: set[str] = set()
    for child in root.iterdir():
        if not child.is_dir() or child.name.startswith("."):
            continue
        if not ((child / "build.gradle.kts").is_file() or (child / "build.gradle").is_file()):
            continue
        if any((child / "src").glob("**/*.kt")):
            modules.add(child.name)
    return modules


def source_files(root: pathlib.Path, modules: list[str]) -> tuple[dict[str, int], dict[str, int]]:
    production: dict[str, int] = {}
    tests: dict[str, int] = {}
    for module in modules:
        module_root = root / module
        if not module_root.is_dir():
            raise ConfigurationError(f"configured source module is missing: {module}")
        for path in sorted(module_root.glob("src/**/*.kt")):
            relative = path.relative_to(root).as_posix()
            parts = path.relative_to(module_root).parts
            if len(parts) < 3 or parts[0] != "src":
                continue
            owner = tests if parts[1] in {"test", "androidTest", "testFixtures"} else production
            owner[relative] = physical_line_count(path)
    return production, tests


def authoritative_baseline_paths(root: pathlib.Path) -> set[str]:
    result = subprocess.run(
        [
            "git",
            "ls-files",
            "--cached",
            "--others",
            "--exclude-standard",
            "--",
            "config/detekt/baseline-*.xml",
        ],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise ConfigurationError(f"cannot enumerate authoritative Detekt baselines: {result.stderr.strip()}")
    return {line for line in result.stdout.splitlines() if line}


def parse_baseline_text(text: str, label: str) -> tuple[collections.Counter[str], set[str]]:
    try:
        root = ElementTree.fromstring(text)
    except ElementTree.ParseError as error:
        raise ConfigurationError(f"malformed Detekt baseline {label}: {error}") from error
    if root.tag != "SmellBaseline":
        raise ConfigurationError(f"unexpected Detekt baseline root in {label}: {root.tag}")
    issues = root.find("CurrentIssues")
    if issues is None:
        raise ConfigurationError(f"Detekt baseline lacks CurrentIssues: {label}")
    identifiers: set[str] = set()
    counts: collections.Counter[str] = collections.Counter()
    for issue in issues.findall("ID"):
        identifier = issue.text
        if identifier is None or ":" not in identifier:
            raise ConfigurationError(f"invalid Detekt issue ID in {label}")
        if identifier in identifiers:
            raise ConfigurationError(f"duplicate Detekt issue ID in {label}: {identifier}")
        identifiers.add(identifier)
        counts[identifier.split(":", 1)[0]] += 1
    return counts, identifiers


def parse_baseline_file(path: pathlib.Path) -> tuple[collections.Counter[str], set[str]]:
    try:
        return parse_baseline_text(path.read_text(encoding="utf-8"), str(path))
    except (OSError, UnicodeError) as error:
        raise ConfigurationError(f"cannot read Detekt baseline {path}: {error}") from error


def baseline_at_ref(root: pathlib.Path, ref: str, relative_path: str) -> set[str] | None:
    result = subprocess.run(
        ["git", "show", f"{ref}:{relative_path}"],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None
    return parse_baseline_text(result.stdout, f"{ref}:{relative_path}")[1]


def audit(root: pathlib.Path, config: dict[str, Any], base: str | None = None) -> dict[str, Any]:
    validate_config(config)
    configured_modules = set(config["sourceModules"])
    discovered_modules = discover_kotlin_modules(root)
    if configured_modules != discovered_modules:
        missing = sorted(discovered_modules - configured_modules)
        stale = sorted(configured_modules - discovered_modules)
        details = []
        if missing:
            details.append(f"undeclared Kotlin modules {missing}")
        if stale:
            details.append(f"configured modules without Kotlin sources {stale}")
        raise ConfigurationError("sourceModules mismatch: " + "; ".join(details))
    configured_baselines = set(config["detektBaselines"])
    authoritative_baselines = authoritative_baseline_paths(root)
    if configured_baselines != authoritative_baselines:
        missing = sorted(authoritative_baselines - configured_baselines)
        stale = sorted(configured_baselines - authoritative_baselines)
        details = []
        if missing:
            details.append(f"unconfigured baseline files {missing}")
        if stale:
            details.append(f"configured baseline paths absent from repository {stale}")
        raise ConfigurationError("detektBaselines mismatch: " + "; ".join(details))
    production, tests = source_files(root, config["sourceModules"])
    line_budgets = config["lineBudgets"]
    production_limit = line_budgets["production"]
    test_limit = line_budgets["test"]
    exceptions: dict[str, int] = line_budgets["productionExceptions"]
    errors: list[str] = []

    for path, budget in exceptions.items():
        lines = production.get(path)
        if lines is None:
            errors.append(f"configured production hotspot is missing or moved: {path}")
        elif lines <= production_limit:
            errors.append(f"stale production hotspot exception {path}: {lines} <= {production_limit}")
        elif lines > budget:
            errors.append(f"production hotspot grew: {path} has {lines} lines, budget {budget}")
    for path, lines in production.items():
        if lines > production_limit and path not in exceptions:
            errors.append(f"unbudgeted production hotspot: {path} has {lines} lines, limit {production_limit}")
    for path, lines in tests.items():
        if lines > test_limit:
            errors.append(f"oversized test file: {path} has {lines} lines, limit {test_limit}")

    baseline_report: dict[str, dict[str, int]] = {}
    for relative_path, expected_counts in config["detektBaselines"].items():
        counts, current_ids = parse_baseline_file(root / relative_path)
        observed_counts = dict(sorted(counts.items()))
        baseline_report[relative_path] = observed_counts
        if observed_counts != expected_counts:
            errors.append(
                f"Detekt baseline debt changed for {relative_path}: "
                f"expected {expected_counts}, found {observed_counts}"
            )
        if base:
            base_ids = baseline_at_ref(root, base, relative_path)
            if base_ids is None:
                if current_ids:
                    errors.append(f"cannot compare non-empty baseline absent at {base}: {relative_path}")
            else:
                additions = sorted(current_ids - base_ids)
                if additions:
                    errors.append(
                        f"Detekt baseline gained IDs versus {base} in {relative_path}: "
                        + ", ".join(additions)
                    )

    return {
        "status": "failed" if errors else "passed",
        "lineBudgets": {
            "production": production_limit,
            "test": test_limit,
            "productionExceptions": {
                path: {"lines": production.get(path), "budget": budget}
                for path, budget in sorted(exceptions.items())
            },
            "largestProduction": sorted(production.items(), key=lambda item: (-item[1], item[0]))[:20],
            "largestTests": sorted(tests.items(), key=lambda item: (-item[1], item[0]))[:20],
        },
        "detektBaselines": baseline_report,
        "errors": errors,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    parser.add_argument("--config", type=pathlib.Path, default=pathlib.Path("config/quality/hotspot-budgets.json"))
    parser.add_argument("--report", type=pathlib.Path, default=pathlib.Path("build/reports/quality/hotspots.json"))
    parser.add_argument("--base", default=None)
    arguments = parser.parse_args(argv)
    root = arguments.root.resolve()
    config_path = arguments.config if arguments.config.is_absolute() else root / arguments.config
    report_path = arguments.report if arguments.report.is_absolute() else root / arguments.report
    try:
        report = audit(root, read_json(config_path), arguments.base or None)
    except ConfigurationError as error:
        report = {"status": "configuration-error", "errors": [str(error)]}
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for error in report["errors"]:
        print(f"ERROR: {error}", file=sys.stderr)
    if report["status"] != "passed":
        return 1
    print(
        "Hotspot gate passed: "
        f"production <= {report['lineBudgets']['production']} lines unless explicitly frozen; "
        f"tests <= {report['lineBudgets']['test']} lines; Detekt debt exact."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
