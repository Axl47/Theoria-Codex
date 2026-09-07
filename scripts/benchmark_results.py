#!/usr/bin/env python3
"""Capture and compare complete physical AndroidX macrobenchmark runs without contacting a device.

record archives the ENTIRE report directory, keeps original paths and SHA-256 evidence, and binds
measurements to APKs, fixture source/assets, device, compilation mode, and workload contract.
calibrate requires at least three distinct full reports from one target APK. Each upper limit is
max(baseline full-run summaries) + (max - min). This gives one observed inter-run noise span of
headroom; it is an empirical screening rule, not a statistical confidence interval. Zero dispersion
means zero headroom. compare permits a changed target APK, but requires the same measurement setup.
A failure is a regression signal to investigate with the preserved iteration traces.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import shutil
import statistics
import sys
from datetime import datetime, timezone
from typing import Any

SCHEMA_VERSION = 2
BENCHMARK_CLASS = "com.theoriacodex.macrobenchmark.TheoriaMacrobenchmark"
FIXTURE_DIRECTORIES = ("app/src/benchmarkRelease", "app/src/fixtures")


class EvidenceError(ValueError):
    """Missing, changed, or incompatible benchmark evidence."""


def metric(name: str, statistic: str = "maximum", sampled: bool = False) -> dict[str, str]:
    return {"name": name, "group": "sampledMetrics" if sampled else "metrics", "statistic": statistic}


def default_contract() -> dict[str, Any]:
    frames = [metric("frameDurationCpuMsFixture", "P95", True), metric("frameOverrunMsFixture", "P95", True)]
    memory = [metric("memoryFixtureRssAnonMaxKb"), metric("memoryFixtureGpuMaxKb")]
    legacy_preview = [metric("previewPrepareCount"), metric("mediaLoadCount")]
    churn = [metric(f"previewPlayer{action}Count") for action in ("Create", "Prepare", "Rebind", "Cool", "Release")]
    specs = {
        "coldStartup": (10, [metric("timeToInitialDisplayMs", "median")]),
        "warmStartup": (10, [metric("timeToInitialDisplayMs", "median")]),
        "searchConcurrentAutoplayScroll": (5, frames + memory + legacy_preview),
        "searchDurationEnrichmentConcurrentAutoplayScroll": (5, frames + memory + legacy_preview + [metric("durationBatchSumMs")]),
        "viewerRepeatedSwipes": (5, frames + memory + [metric("viewerPrepareCount"), metric("mediaLoadCount")]),
        "mixedMediaFiveFeedJourney": (5, frames + memory + churn + [metric("mediaLoadCount")]),
        "durationAcquisitionFiveFeedJourney": (5, frames + memory + churn + [metric("mediaLoadCount"), metric("fixtureByteRangeCount"), metric("journeyDurationBatchSumMs")]),
    }
    return {
        "version": "production-five-feed-v2",
        "class_name": BENCHMARK_CLASS,
        "scenarios": {name: {"iterations": count, "metrics": metrics} for name, (count, metrics) in specs.items()},
    }


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def digest(value: Any) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def file_hash(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def ensure_finite(value: Any, location: str = "report") -> None:
    if isinstance(value, float) and not math.isfinite(value):
        raise EvidenceError(f"Nonfinite number in {location}")
    if isinstance(value, dict):
        for key, child in value.items():
            ensure_finite(child, f"{location}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            ensure_finite(child, f"{location}[{index}]")


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text())
    except (OSError, ValueError) as error:
        raise EvidenceError(f"Cannot read JSON {path}: {error}") from error
    if not isinstance(value, dict):
        raise EvidenceError(f"Expected JSON object: {path}")
    ensure_finite(value)
    return value


def write_new_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x") as stream:
        json.dump(value, stream, indent=2, sort_keys=True, allow_nan=False)
        stream.write("\n")


def number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value):
        raise EvidenceError(f"Missing or nonfinite metric {label}")
    return float(value)


def validate_contract(contract: dict[str, Any]) -> None:
    if not contract.get("version") or not contract.get("class_name") or not contract.get("scenarios"):
        raise EvidenceError("Contract requires a version, class_name, and nonempty scenarios")
    for name, spec in contract["scenarios"].items():
        if not isinstance(spec.get("iterations"), int) or spec["iterations"] < 2 or not spec.get("metrics"):
            raise EvidenceError(f"Contract requires >=2 iterations and metrics for {name}")
        keys = []
        for definition in spec["metrics"]:
            group, stat = definition.get("group"), definition.get("statistic")
            if not definition.get("name") or (group, stat) not in {
                ("metrics", "median"), ("metrics", "maximum"), ("sampledMetrics", "P95"),
            }:
                raise EvidenceError(f"Invalid metric definition in {name}: {definition}")
            keys.append((group, definition["name"], stat))
        if len(keys) != len(set(keys)):
            raise EvidenceError(f"Duplicate metric definition in {name}")


def extract_measurements(report: dict[str, Any], contract: dict[str, Any]) -> tuple[dict[str, float], dict[str, Any]]:
    validate_contract(contract)
    benchmarks = report.get("benchmarks")
    if not isinstance(benchmarks, list):
        raise EvidenceError("Missing benchmark methods")
    by_name = {entry.get("name"): entry for entry in benchmarks}
    if len(by_name) != len(benchmarks) or set(by_name) != set(contract["scenarios"]):
        raise EvidenceError(f"Benchmark method set differs from contract: actual={sorted(str(n) for n in by_name)}, required={sorted(contract['scenarios'])}")
    measurements: dict[str, float] = {}
    layout: dict[str, Any] = {}
    for name, spec in contract["scenarios"].items():
        entry = by_name[name]
        if entry.get("className") != contract["class_name"] or entry.get("repeatIterations") != spec["iterations"]:
            raise EvidenceError(f"Wrong benchmark class or incomplete iterations for {name}")
        layout[name] = {key: entry.get(key) for key in ("className", "params", "repeatIterations", "warmupIterations")}
        for definition in spec["metrics"]:
            group, field, stat = definition["group"], definition["name"], definition["statistic"]
            label = f"{name}/{group}/{field}/{stat}"
            values = entry.get(group, {}).get(field, {})
            runs = values.get("runs")
            if not isinstance(runs, list) or len(runs) != spec["iterations"]:
                raise EvidenceError(f"Missing or incomplete iteration measurements: {label}")
            if group == "sampledMetrics":
                for index, samples in enumerate(runs):
                    if not isinstance(samples, list) or not samples:
                        raise EvidenceError(f"Empty sampled iteration: {label} iteration {index}")
                    for sample in samples:
                        number(sample, label)
                summary = number(values.get(stat), label)
            else:
                scalars = [number(value, label) for value in runs]
                summary = max(scalars) if stat == "maximum" else statistics.median(scalars)
                exported = number(values.get(stat), label)
                if not math.isclose(summary, exported, rel_tol=1e-9, abs_tol=1e-9):
                    raise EvidenceError(f"Summary does not match raw iterations: {label}")
            measurements[label] = summary
    return measurements, layout


def trace_files(report: dict[str, Any], directory: Path) -> list[Path]:
    paths = []
    for entry in report["benchmarks"]:
        traces = [item for item in entry.get("profilerOutputs", []) if item.get("type") == "PerfettoTrace"]
        if len(traces) != entry["repeatIterations"]:
            raise EvidenceError(f"Missing per-iteration Perfetto evidence for {entry['name']}")
        for trace in traces:
            filename = trace.get("filename")
            if not isinstance(filename, str) or Path(filename).is_absolute():
                raise EvidenceError("Invalid Perfetto filename")
            path = (directory / filename).resolve()
            if not path.is_relative_to(directory.resolve()) or not path.is_file() or path.stat().st_size == 0:
                raise EvidenceError(f"Missing or unsafe Perfetto path: {filename}")
            paths.append(path)
    if len(paths) != len(set(paths)):
        raise EvidenceError("A Perfetto trace was reused for multiple iterations")
    return paths


def validate_device_and_thermal_state(report: dict[str, Any], device_kind: str) -> None:
    context = report.get("context", {})
    build = context.get("build", {})
    if not build.get("fingerprint") or not build.get("model") or not build.get("version", {}).get("sdk"):
        raise EvidenceError("Report lacks device fingerprint, model, or SDK")
    fields = " ".join(str(build.get(key, "")) for key in
                      ("brand", "device", "fingerprint", "hardware", "manufacturer", "model", "product")).lower()
    emulator = context.get("isEmulator") is True or build.get("isEmulator") is True or any(
        marker in fields for marker in ("sdk_gphone", "google_sdk", "android sdk built for", "goldfish",
                                       "ranchu", "genymotion", "vbox86", "emulator")
    ) or str(build["fingerprint"]).lower().startswith(("generic/", "generic_x86/", "generic_x86_64/")) or str(
        build.get("device", "")
    ).lower() in {"generic", "generic_x86", "generic_x86_64", "emu64a", "emu64x"}
    if device_kind == "physical" and emulator:
        raise EvidenceError("Raw build context identifies an emulator despite physical device metadata")
    for entry in report["benchmarks"]:
        throttle = number(entry.get("thermalThrottleSleepSeconds"), f"{entry['name']} thermalThrottleSleepSeconds")
        if throttle != 0:
            raise EvidenceError(f"Thermally throttled run {entry['name']}: cool the device and rerun the full suite")


def workload_snapshot(root: Path, contract: dict[str, Any]) -> dict[str, Any]:
    """Fingerprint target-side fixture code/resources separately from changeable production code."""
    files = []
    for relative_directory in FIXTURE_DIRECTORIES:
        directory = root / relative_directory
        paths = sorted(path for path in directory.rglob("*") if path.is_file())
        if not paths:
            raise EvidenceError(f"Missing workload sources/assets: {directory}")
        for path in paths:
            if not path.resolve().is_relative_to(root):
                raise EvidenceError(f"Workload file points outside its checkout: {path}")
            files.append({"path": path.relative_to(root).as_posix(), "sha256": file_hash(path)})
    manifest = {"files": files, "contract_sha256": digest(contract)}
    return {**manifest, "sha256": digest(manifest), "original_root": str(root)}


def validate_workload_snapshot(record: dict[str, Any], capture_directory: Path) -> None:
    workload = record.get("workload", {})
    manifest = {"files": workload.get("files"), "contract_sha256": workload.get("contract_sha256")}
    if not manifest["files"] or manifest["contract_sha256"] != digest(record["contract"]) or digest(manifest) != workload.get("sha256"):
        raise EvidenceError("Missing or changed fixture source/assets manifest")
    archived_root = (capture_directory / "workload").resolve()
    paths = []
    for item in manifest["files"]:
        path = (archived_root / item["path"]).resolve()
        if not path.is_relative_to(archived_root) or not path.is_file() or file_hash(path) != item["sha256"]:
            raise EvidenceError(f"Archived workload file missing or changed: {item['path']}")
        paths.append(item["path"])
    if len(paths) != len(set(paths)):
        raise EvidenceError("Duplicate workload manifest paths")


def record_run(report_path: Path, apk: Path, harness_apk: Path, output: Path,
               device_serial: str, device_kind: str, compilation: str,
               contract: dict[str, Any] | None = None, fixture_root: Path | None = None) -> Path:
    report_path, apk, harness_apk, output = (path.resolve() for path in (report_path, apk, harness_apk, output))
    contract = contract or default_contract()
    if not device_serial.strip() or not compilation.strip() or device_kind not in {"physical", "emulator"}:
        raise EvidenceError("Device serial, device kind, and target compilation mode are required")
    if output.exists() or output.is_relative_to(report_path.parent):
        raise EvidenceError("Capture output must be new and outside the report directory")
    report = read_json(report_path)
    measurements, layout = extract_measurements(report, contract)
    validate_device_and_thermal_state(report, device_kind)
    context = report["context"]
    fixture_root = (fixture_root or Path(__file__).resolve().parents[1]).resolve()
    workload = workload_snapshot(fixture_root, contract)
    traces = trace_files(report, report_path.parent)
    artifacts = []
    for path in (apk, harness_apk):
        if not path.is_file() or path.stat().st_size == 0:
            raise EvidenceError(f"Missing APK: {path}")
        artifacts.append({"original_path": str(path), "sha256": file_hash(path)})
    raw_hash = file_hash(report_path)
    # Copy all additional outputs, not just JSON and selected traces. Never remove the originals.
    output.mkdir(parents=True)
    shutil.copytree(report_path.parent, output / "artifacts")
    for item in workload["files"]:
        archived_file = output / "workload" / item["path"]
        archived_file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(fixture_root / item["path"], archived_file)
        if file_hash(archived_file) != item["sha256"]:
            raise EvidenceError(f"Workload source changed during capture: {item['path']}")
    archived_report = output / "artifacts" / report_path.name
    if file_hash(archived_report) != raw_hash:
        raise EvidenceError("Report changed while being archived; capture again after the run completes")
    trace_evidence = []
    for trace in traces:
        relative = trace.relative_to(report_path.parent)
        archived = output / "artifacts" / relative
        original_hash = file_hash(trace)
        if file_hash(archived) != original_hash:
            raise EvidenceError(f"Trace changed during capture: {trace}")
        trace_evidence.append({"original_path": str(trace), "archived_path": str(archived), "sha256": original_hash})
    record = {
        "schema_version": SCHEMA_VERSION,
        "recorded_at": datetime.now(timezone.utc).isoformat(),
        "target_apk": artifacts[0], "harness_apk": artifacts[1],
        "device_serial": device_serial, "device_kind": device_kind, "compilation": compilation,
        "contract": contract, "workload": workload, "report_context": context, "layout": layout,
        "report": {"original_path": str(report_path), "archived_path": str(archived_report),
                   "sha256": raw_hash, "canonical_sha256": digest(report)},
        "traces": trace_evidence, "measurements": measurements,
    }
    destination = output / "run.json"
    write_new_json(destination, record)
    return destination


def load_run(path: Path) -> dict[str, Any]:
    record = read_json(path)
    if record.get("schema_version") != SCHEMA_VERSION:
        raise EvidenceError(f"Unsupported capture schema: {path}")
    report_path = Path(record["report"]["archived_path"])
    report = read_json(report_path)
    if file_hash(report_path) != record["report"]["sha256"] or digest(report) != record["report"]["canonical_sha256"]:
        raise EvidenceError(f"Archived report changed: {report_path}")
    measurements, layout = extract_measurements(report, record["contract"])
    validate_device_and_thermal_state(report, record["device_kind"])
    validate_workload_snapshot(record, path.parent)
    if measurements != record["measurements"] or layout != record["layout"] or report.get("context") != record["report_context"]:
        raise EvidenceError(f"Capture metadata does not match raw report: {path}")
    expected_traces = trace_files(report, report_path.parent)
    if {str(p) for p in expected_traces} != {item["archived_path"] for item in record["traces"]}:
        raise EvidenceError(f"Trace manifest differs from raw report: {path}")
    for item in record["traces"]:
        if file_hash(Path(item["archived_path"])) != item["sha256"]:
            raise EvidenceError(f"Archived trace changed: {item['archived_path']}")
    return record


def setup_identity(record: dict[str, Any]) -> dict[str, Any]:
    return {**{key: record[key] for key in ("device_serial", "device_kind", "compilation", "contract", "report_context", "layout")},
            "harness_sha256": record["harness_apk"]["sha256"], "workload_sha256": record["workload"]["sha256"]}


def calibrate(run_paths: list[Path]) -> dict[str, Any]:
    if len(run_paths) < 3:
        raise EvidenceError("Calibration needs at least three distinct complete baseline runs")
    runs = [load_run(path) for path in run_paths]
    identity = setup_identity(runs[0])
    if identity["device_kind"] != "physical":
        raise EvidenceError("Physical-device baseline required; emulator runs are exploratory only")
    if any(setup_identity(run) != identity for run in runs[1:]):
        raise EvidenceError("Baseline device, OS, harness, compilation, or workload differs")
    hashes = [run["report"]["canonical_sha256"] for run in runs]
    if len(set(hashes)) != len(hashes):
        raise EvidenceError("Repeated raw report cannot count as distinct baseline runs")
    apk_hashes = {run["target_apk"]["sha256"] for run in runs}
    if len(apk_hashes) != 1:
        raise EvidenceError("Every baseline run must use the same target APK")
    limits = {}
    for key in runs[0]["measurements"]:
        values = [run["measurements"][key] for run in runs]
        low, high = min(values), max(values)
        observed_range = number(high - low, f"{key} calibration range")
        upper_limit = number(high + observed_range, f"{key} calibration limit")
        limits[key] = {"baseline_run_values": values, "baseline_median": statistics.median(values),
                       "observed_range": observed_range, "upper_limit": upper_limit}
    return {"schema_version": SCHEMA_VERSION, "kind": "calibration", "identity": identity,
            "target_apk_sha256": next(iter(apk_hashes)),
            "baseline_records": [str(path.resolve()) for path in run_paths], "report_hashes": hashes,
            "formula": "max(full_run_summaries) + (max(full_run_summaries) - min(full_run_summaries))",
            "limits": limits}


def compare(baseline_path: Path, candidate_path: Path) -> dict[str, Any]:
    baseline = read_json(baseline_path)
    if baseline.get("schema_version") != SCHEMA_VERSION or baseline.get("kind") != "calibration":
        raise EvidenceError("Not a supported calibration document")
    # Re-derive the limits from the original archived evidence; a manually widened gate cannot pass.
    verified = calibrate([Path(path) for path in baseline["baseline_records"]])
    if baseline != verified:
        raise EvidenceError("Calibration differs from its baseline evidence")
    candidate = load_run(candidate_path)
    if setup_identity(candidate) != baseline["identity"]:
        raise EvidenceError("Candidate device, OS, harness, compilation, or workload differs from calibration")
    if candidate["report"]["canonical_sha256"] in baseline["report_hashes"]:
        raise EvidenceError("Candidate must be a new full run, not reused baseline evidence")
    results = {}
    for key, limit in baseline["limits"].items():
        value = candidate["measurements"][key]
        results[key] = {**limit, "candidate": value, "regressed": value > limit["upper_limit"],
                        "delta": value - limit["baseline_median"]}
    regressions = [key for key, result in results.items() if result["regressed"]]
    return {"schema_version": SCHEMA_VERSION, "kind": "comparison", "passed": not regressions,
            "regressions": regressions, "metrics": results,
            "baseline": str(baseline_path.resolve()), "candidate": str(candidate_path.resolve()),
            "baseline_apk_sha256": baseline["target_apk_sha256"], "candidate_apk_sha256": candidate["target_apk"]["sha256"]}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    record = sub.add_parser("record", help="Archive a complete raw report directory and bind the run metadata")
    for name in ("report", "apk", "harness-apk", "output"):
        record.add_argument(f"--{name}", type=Path, required=True)
    record.add_argument("--device-serial", required=True)
    record.add_argument("--device-kind", choices=("physical", "emulator"), required=True)
    record.add_argument("--compilation", required=True, help="Actual target CompilationMode, including baseline-profile mode and warmup count")
    record.add_argument("--contract", type=Path, help="Explicit alternative full-suite contract; differing contracts cannot compare")
    record.add_argument("--fixture-root", type=Path, help="Checkout containing app/src/benchmarkRelease and app/src/fixtures; defaults to this script's checkout")
    calibration = sub.add_parser("calibrate", help="Derive absolute limits from >=3 distinct same-APK full runs")
    calibration.add_argument("--runs", nargs="+", type=Path, required=True)
    calibration.add_argument("--output", type=Path, required=True)
    comparison = sub.add_parser("compare", help="Compare a fresh complete run; exit 1 on regression, 2 on invalid evidence")
    comparison.add_argument("--baseline", type=Path, required=True)
    comparison.add_argument("--candidate", type=Path, required=True)
    comparison.add_argument("--output", type=Path, required=True)
    sub.add_parser("contract", help="Print the required default method/metric/iteration contract")
    args = parser.parse_args(argv)
    try:
        if args.command == "record":
            result = record_run(args.report, args.apk, args.harness_apk, args.output, args.device_serial,
                                args.device_kind, args.compilation, read_json(args.contract) if args.contract else None, args.fixture_root)
            print(f"Recorded complete run: {result}")
        elif args.command == "calibrate":
            result = calibrate(args.runs)
            write_new_json(args.output, result)
            print(f"Calibrated {len(result['limits'])} metrics from {len(args.runs)} distinct full runs: {args.output}")
        elif args.command == "compare":
            result = compare(args.baseline, args.candidate)
            write_new_json(args.output, result)
            print(f"{'PASS' if result['passed'] else 'REGRESSION'}: {len(result['regressions'])} metrics exceed observed-noise limits; {args.output}")
            for key in result["regressions"]:
                item = result["metrics"][key]
                print(f"  {key}: {item['candidate']:g} > {item['upper_limit']:g}")
            return 0 if result["passed"] else 1
        else:
            print(json.dumps(default_contract(), indent=2))
        return 0
    except (EvidenceError, OSError, KeyError, TypeError) as error:
        print(f"Invalid benchmark evidence: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
