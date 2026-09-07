from __future__ import annotations

import contextlib
import io
import json
import shutil
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import benchmark_results as benchmark


class BenchmarkResultsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.apk = self.root / "target.apk"
        self.harness = self.root / "runner.apk"
        self.apk.write_bytes(b"fixed target artifact")
        self.harness.write_bytes(b"fixed harness artifact")
        self.index = 0
        self.fixture_root = self.root / "checkout"
        for directory in benchmark.FIXTURE_DIRECTORIES:
            (self.fixture_root / directory).mkdir(parents=True)
        (self.fixture_root / "app/src/benchmarkRelease/Fixture.kt").write_text("frozen workload code")
        asset = self.fixture_root / "app/src/fixtures/res/raw/clip.mp4"
        asset.parent.mkdir(parents=True)
        asset.write_bytes(b"frozen workload video")
        self.contract = {
            "version": "test-five-feed-v2", "class_name": benchmark.BENCHMARK_CLASS,
            "scenarios": {
                "coldStartup": {"iterations": 3, "metrics": [benchmark.metric("timeToInitialDisplayMs", "median")]},
                "mixedMediaFiveFeedJourney": {"iterations": 3, "metrics": [
                    benchmark.metric("frameDurationCpuMsFixture", "P95", True),
                    benchmark.metric("memoryFixtureRssAnonMaxKb"), benchmark.metric("previewPlayerCreateCount"),
                ]},
                "durationAcquisitionFiveFeedJourney": {"iterations": 3, "metrics": [benchmark.metric("fixtureByteRangeCount")]},
            },
        }
        self.values = {
            "timeToInitialDisplayMs": 300, "frameDurationCpuMsFixture": 8,
            "memoryFixtureRssAnonMaxKb": 120_000, "previewPlayerCreateCount": 10, "fixtureByteRangeCount": 24,
        }

    def raw_report(self, values=None, context=None) -> Path:
        self.index += 1
        directory = self.root / f"raw-{self.index}"
        directory.mkdir()
        values = self.values | (values or {})
        report = {"context": context or {
            "build": {"model": "SM-S926U", "fingerprint": "samsung/test:16/release-keys", "version": {"sdk": 36}},
            "cpuLocked": True, "compilationMode": "run-from-apk",
        }, "benchmarks": []}
        for name, spec in self.contract["scenarios"].items():
            entry = {"name": name, "className": benchmark.BENCHMARK_CLASS, "params": {}, "repeatIterations": 3,
                     "warmupIterations": 0, "thermalThrottleSleepSeconds": 0, "metrics": {}, "sampledMetrics": {}, "profilerOutputs": []}
            for definition in spec["metrics"]:
                value = values[definition["name"]]
                sampled = definition["group"] == "sampledMetrics"
                entry[definition["group"]][definition["name"]] = {
                    definition["statistic"]: value,
                    "runs": [[value, value, value] for _ in range(3)] if sampled else [value] * 3,
                }
            for iteration in range(3):
                filename = f"{name}-{self.index}-iter{iteration}.perfetto-trace"
                (directory / filename).write_bytes(f"unique trace {self.index} {name} {iteration}".encode())
                entry["profilerOutputs"].append({"type": "PerfettoTrace", "label": f"Iteration {iteration}", "filename": filename})
            report["benchmarks"].append(entry)
        (directory / "diagnostics.txt").write_text("additional evidence must survive")
        path = directory / "benchmarkData.json"
        path.write_text(json.dumps(report))
        return path

    def capture(self, values=None, *, report=None, **metadata) -> Path:
        report = report or self.raw_report(values)
        destination = self.root / f"capture-{len(list(self.root.glob('capture-*')))}"
        options = {"device_serial": "fixture-phone-1", "device_kind": "physical",
                   "compilation": "Partial(Require,warmupIterations=0)", "contract": self.contract, "fixture_root": self.fixture_root}
        options.update(metadata)
        return benchmark.record_run(report, self.apk, self.harness, destination, **options)

    def calibrated(self, changes=None) -> Path:
        changes = changes or [
            {"timeToInitialDisplayMs": 300, "frameDurationCpuMsFixture": 8, "memoryFixtureRssAnonMaxKb": 120_000},
            {"timeToInitialDisplayMs": 305, "frameDurationCpuMsFixture": 8.1, "memoryFixtureRssAnonMaxKb": 121_000},
            {"timeToInitialDisplayMs": 310, "frameDurationCpuMsFixture": 8.2, "memoryFixtureRssAnonMaxKb": 119_000},
        ]
        runs = [self.capture(change) for change in changes]
        path = self.root / "calibration.json"
        benchmark.write_new_json(path, benchmark.calibrate(runs))
        return path

    def test_record_archives_all_outputs_and_retains_original_paths_and_hashes(self) -> None:
        raw = self.raw_report()
        record_path = self.capture(report=raw)
        record = benchmark.load_run(record_path)
        self.assertEqual(str(raw.resolve()), record["report"]["original_path"])
        self.assertEqual(benchmark.file_hash(raw), record["report"]["sha256"])
        self.assertTrue(raw.exists())
        self.assertEqual(9, len(record["traces"]))
        self.assertEqual("additional evidence must survive", (record_path.parent / "artifacts/diagnostics.txt").read_text())
        self.assertEqual(benchmark.file_hash(self.apk), record["target_apk"]["sha256"])

    def test_calibration_formula_uses_observed_absolute_noise_and_accepts_compatible_candidate(self) -> None:
        baseline = self.calibrated()
        limits = benchmark.read_json(baseline)["limits"]
        startup = limits["coldStartup/metrics/timeToInitialDisplayMs/median"]
        self.assertEqual([300, 305, 310], startup["baseline_run_values"])
        self.assertEqual(10, startup["observed_range"])
        self.assertEqual(320, startup["upper_limit"])
        self.apk.write_bytes(b"candidate may change production code")
        result = benchmark.compare(baseline, self.capture({"timeToInitialDisplayMs": 315}))
        self.assertTrue(result["passed"])
        self.assertNotEqual(result["baseline_apk_sha256"], result["candidate_apk_sha256"])

    def test_injected_startup_frame_tail_peak_memory_churn_and_transport_regressions_fail_individually(self) -> None:
        baseline = self.calibrated()
        for name, value in {
            "timeToInitialDisplayMs": 360,
            "frameDurationCpuMsFixture": 11,
            "memoryFixtureRssAnonMaxKb": 160_000,
            "previewPlayerCreateCount": 18,
            "fixtureByteRangeCount": 48,
        }.items():
            with self.subTest(metric=name):
                result = benchmark.compare(baseline, self.capture({name: value}))
                self.assertFalse(result["passed"])
                self.assertEqual(1, len(result["regressions"]))
                self.assertIn(name, result["regressions"][0])

    def test_zero_baseline_never_divides_by_zero_or_hides_new_work(self) -> None:
        baseline = self.calibrated([{"previewPlayerCreateCount": 0}] * 3)
        candidate = self.capture({"previewPlayerCreateCount": 1})
        result = benchmark.compare(baseline, candidate)
        key = "mixedMediaFiveFeedJourney/metrics/previewPlayerCreateCount/maximum"
        self.assertEqual(0, result["metrics"][key]["upper_limit"])
        self.assertTrue(result["metrics"][key]["regressed"])

    def test_calibration_requires_distinct_complete_runs_and_the_same_target_apk(self) -> None:
        first, second = self.capture(), self.capture()
        with self.assertRaisesRegex(benchmark.EvidenceError, "at least three"):
            benchmark.calibrate([first, second])
        with self.assertRaisesRegex(benchmark.EvidenceError, "Repeated raw report"):
            benchmark.calibrate([first, second, first])
        # Reformatting and recapturing a report does not turn one run into two.
        original = Path(benchmark.read_json(first)["report"]["original_path"])
        original.write_text(json.dumps(json.loads(original.read_text()), indent=4))
        recaptured = self.capture(report=original)
        with self.assertRaisesRegex(benchmark.EvidenceError, "Repeated raw report"):
            benchmark.calibrate([first, second, recaptured])
        self.apk.write_bytes(b"different target revision")
        third = self.capture()
        with self.assertRaisesRegex(benchmark.EvidenceError, "same target APK"):
            benchmark.calibrate([first, second, third])

    def test_different_device_os_compilation_harness_or_workload_cannot_compare(self) -> None:
        baseline = self.calibrated()
        for metadata in ({"device_serial": "other-phone"}, {"device_kind": "emulator"}, {"compilation": "Full"},
                         {"contract": self.contract | {"version": "changed-workload"}}):
            with self.subTest(metadata=metadata):
                with self.assertRaisesRegex(benchmark.EvidenceError, "differs from calibration"):
                    benchmark.compare(baseline, self.capture(**metadata))
        report = self.raw_report()
        changed = json.loads(report.read_text())
        changed["context"]["build"]["fingerprint"] = "new OS build"
        report.write_text(json.dumps(changed))
        with self.assertRaisesRegex(benchmark.EvidenceError, "differs from calibration"):
            benchmark.compare(baseline, self.capture(report=report))
        self.harness.write_bytes(b"changed harness")
        with self.assertRaisesRegex(benchmark.EvidenceError, "differs from calibration"):
            benchmark.compare(baseline, self.capture())

    def test_emulator_captures_cannot_be_physical_calibration(self) -> None:
        runs = [self.capture(device_kind="emulator") for _ in range(3)]
        with self.assertRaisesRegex(benchmark.EvidenceError, "Physical-device"):
            benchmark.calibrate(runs)

    def test_selective_methods_missing_metrics_and_partial_iterations_are_rejected(self) -> None:
        for corruption in ("scenario", "metric", "iteration", "samples", "trace", "summary"):
            with self.subTest(corruption=corruption):
                report = self.raw_report()
                data = json.loads(report.read_text())
                if corruption == "scenario":
                    data["benchmarks"].pop()
                elif corruption == "metric":
                    data["benchmarks"][0]["metrics"].clear()
                elif corruption == "iteration":
                    data["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"]["runs"].pop()
                elif corruption == "samples":
                    data["benchmarks"][1]["sampledMetrics"]["frameDurationCpuMsFixture"]["runs"][0] = []
                elif corruption == "trace":
                    data["benchmarks"][0]["profilerOutputs"].pop()
                else:
                    data["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"]["median"] = 999
                report.write_text(json.dumps(data))
                with self.assertRaises(benchmark.EvidenceError):
                    self.capture(report=report)

    def test_nonfinite_summaries_or_raw_samples_are_never_silently_skipped(self) -> None:
        for value in (float("nan"), float("inf"), -float("inf")):
            with self.subTest(value=value):
                with self.assertRaisesRegex(benchmark.EvidenceError, "Nonfinite"):
                    self.capture({"frameDurationCpuMsFixture": value})
        report = self.raw_report()
        data = json.loads(report.read_text())
        data["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"]["runs"][1] = None
        report.write_text(json.dumps(data))
        with self.assertRaisesRegex(benchmark.EvidenceError, "Missing or nonfinite"):
            self.capture(report=report)

    def test_missing_changed_or_reused_trace_evidence_cannot_pass(self) -> None:
        captured = self.capture()
        record = benchmark.read_json(captured)
        Path(record["traces"][0]["archived_path"]).write_bytes(b"modified trace")
        with self.assertRaisesRegex(benchmark.EvidenceError, "Archived trace changed"):
            benchmark.load_run(captured)
        for corrupt in ("missing", "reused", "escape"):
            report = self.raw_report()
            data = json.loads(report.read_text())
            traces = data["benchmarks"][0]["profilerOutputs"]
            if corrupt == "missing":
                (report.parent / traces[0]["filename"]).unlink()
            elif corrupt == "reused":
                traces[1]["filename"] = traces[0]["filename"]
            else:
                traces[0]["filename"] = "../target.apk"
            report.write_text(json.dumps(data))
            with self.assertRaises(benchmark.EvidenceError):
                self.capture(report=report)

    def test_changed_report_or_widened_calibration_is_rejected(self) -> None:
        baseline = self.calibrated()
        candidate = self.capture()
        data = benchmark.read_json(baseline)
        next(iter(data["limits"].values()))["upper_limit"] = 1e9
        baseline.write_text(json.dumps(data))
        with self.assertRaisesRegex(benchmark.EvidenceError, "Calibration differs"):
            benchmark.compare(baseline, candidate)
        record = benchmark.read_json(candidate)
        report = Path(record["report"]["archived_path"])
        report.write_text(report.read_text() + " ")
        with self.assertRaisesRegex(benchmark.EvidenceError, "Archived report changed"):
            benchmark.load_run(candidate)

    def test_default_contract_requires_all_seven_full_scenarios_and_cost_dimensions(self) -> None:
        contract = benchmark.default_contract()
        benchmark.validate_contract(contract)
        self.assertEqual(7, len(contract["scenarios"]))
        self.assertEqual(10, contract["scenarios"]["coldStartup"]["iterations"])
        required = {metric["name"] for metric in contract["scenarios"]["durationAcquisitionFiveFeedJourney"]["metrics"]}
        self.assertTrue({"fixtureByteRangeCount", "journeyDurationBatchSumMs", "previewPlayerCreateCount", "memoryFixtureRssAnonMaxKb", "frameDurationCpuMsFixture"}.issubset(required))

    def test_cli_returns_distinct_regression_and_invalid_evidence_statuses(self) -> None:
        baseline = self.calibrated()
        candidate = self.capture({"fixtureByteRangeCount": 48})
        output = self.root / "comparison.json"
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, benchmark.main(["compare", "--baseline", str(baseline), "--candidate", str(candidate), "--output", str(output)]))
            self.assertFalse(benchmark.read_json(output)["passed"])
            self.assertEqual(2, benchmark.main(["compare", "--baseline", str(self.root / "missing.json"), "--candidate", str(candidate), "--output", str(output)]))

    def test_workload_manifest_preserves_code_assets_and_contract_independent_of_checkout_path(self) -> None:
        baseline = self.calibrated()
        record_path = self.capture()
        record = benchmark.load_run(record_path)
        self.assertEqual(
            {"app/src/benchmarkRelease/Fixture.kt", "app/src/fixtures/res/raw/clip.mp4"},
            {item["path"] for item in record["workload"]["files"]},
        )
        self.assertEqual(benchmark.digest(self.contract), record["workload"]["contract_sha256"])
        self.assertEqual(b"frozen workload video", (record_path.parent / "workload/app/src/fixtures/res/raw/clip.mp4").read_bytes())
        # An identical checkout in another directory is still the same workload.
        clone = self.root / "other-checkout"
        shutil.copytree(self.fixture_root, clone)
        self.assertTrue(benchmark.compare(baseline, self.capture(fixture_root=clone))["passed"])

    def test_changed_fixture_source_or_asset_fails_even_when_harness_and_contract_match(self) -> None:
        baseline = self.calibrated()
        for path in (self.fixture_root / "app/src/benchmarkRelease/Fixture.kt",
                     self.fixture_root / "app/src/fixtures/res/raw/clip.mp4"):
            with self.subTest(path=path.name):
                original = path.read_bytes()
                path.write_bytes(original + b" changed workload")
                candidate = self.capture()
                with self.assertRaisesRegex(benchmark.EvidenceError, "workload differs"):
                    benchmark.compare(baseline, candidate)
                path.write_bytes(original)
        # Adding a new fixture resource also changes the full manifest.
        (self.fixture_root / "app/src/fixtures/res/raw/second.mp4").write_bytes(b"extra fixture media")
        with self.assertRaisesRegex(benchmark.EvidenceError, "workload differs"):
            benchmark.compare(baseline, self.capture())

    def test_missing_source_or_modified_archived_workload_cannot_be_calibration_evidence(self) -> None:
        missing = self.root / "missing-checkout"
        with self.assertRaisesRegex(benchmark.EvidenceError, "Missing workload sources"):
            self.capture(fixture_root=missing)
        captured = self.capture()
        archived = captured.parent / "workload/app/src/benchmarkRelease/Fixture.kt"
        archived.write_text("different measured workload")
        with self.assertRaisesRegex(benchmark.EvidenceError, "Archived workload file missing or changed"):
            benchmark.load_run(captured)

    def test_raw_emulator_build_is_rejected_despite_physical_label(self) -> None:
        for field, value in (("model", "sdk_gphone64_x86_64"), ("model", "Android SDK built for x86"),
                             ("hardware", "ranchu"), ("manufacturer", "Genymotion"), ("device", "emu64a"),
                             ("fingerprint", "generic/sdk/generic:16/test-keys")):
            with self.subTest(field=field, value=value):
                report = self.raw_report()
                data = json.loads(report.read_text())
                data["context"]["build"][field] = value
                report.write_text(json.dumps(data))
                with self.assertRaisesRegex(benchmark.EvidenceError, "identifies an emulator"):
                    self.capture(report=report, device_kind="physical")
        report = self.raw_report()
        data = json.loads(report.read_text())
        data["context"]["isEmulator"] = True
        report.write_text(json.dumps(data))
        captured = self.capture(report=report, device_kind="emulator")
        manifest = benchmark.read_json(captured)
        manifest["device_kind"] = "physical"
        captured.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(benchmark.EvidenceError, "identifies an emulator"):
            benchmark.load_run(captured)

    def test_throttled_reports_require_cooldown_and_a_full_rerun(self) -> None:
        for scenario in range(3):
            report = self.raw_report()
            data = json.loads(report.read_text())
            data["benchmarks"][scenario]["thermalThrottleSleepSeconds"] = 30.0
            report.write_text(json.dumps(data))
            with self.assertRaisesRegex(benchmark.EvidenceError, "cool the device and rerun the full suite"):
                self.capture(report=report)
        report = self.raw_report()
        data = json.loads(report.read_text())
        del data["benchmarks"][0]["thermalThrottleSleepSeconds"]
        report.write_text(json.dumps(data))
        with self.assertRaisesRegex(benchmark.EvidenceError, "thermalThrottleSleepSeconds"):
            self.capture(report=report)

    def test_capture_refuses_overwrite_and_keeps_existing_evidence_intact(self) -> None:
        captured = self.capture()
        previous = captured.read_bytes()
        report = self.raw_report()
        with self.assertRaisesRegex(benchmark.EvidenceError, "must be new"):
            benchmark.record_run(report, self.apk, self.harness, captured.parent, "serial", "physical", "Partial", self.contract)
        self.assertEqual(previous, captured.read_bytes())


if __name__ == "__main__":
    unittest.main()
