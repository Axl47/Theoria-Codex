from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPTS_DIR = pathlib.Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = SCRIPTS_DIR.parent
sys.path.insert(0, str(SCRIPTS_DIR))

import check_hotspots as hotspots  # noqa: E402


class HotspotGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary_directory.name)
        self.module = self.root / "sample"
        (self.module / "build.gradle.kts").parent.mkdir(parents=True)
        (self.module / "build.gradle.kts").write_text("plugins {}\n", encoding="utf-8")
        (self.module / "src/main/kotlin").mkdir(parents=True)
        (self.module / "src/test/kotlin").mkdir(parents=True)
        (self.root / "config/detekt").mkdir(parents=True)
        self.production = self.module / "src/main/kotlin/Feature.kt"
        self.test = self.module / "src/test/kotlin/FeatureTest.kt"
        self.baseline = self.root / "config/detekt/baseline-sample.xml"
        self.production.write_text("package sample\n", encoding="utf-8")
        self.test.write_text("package sample\n", encoding="utf-8")
        self.write_baseline([])
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        subprocess.run(["git", "add", "config/detekt/baseline-sample.xml"], cwd=self.root, check=True)
        self.config = {
            "version": 1,
            "sourceModules": ["sample"],
            "lineBudgets": {
                "production": 5,
                "test": 6,
                "productionExceptions": {},
            },
            "detektBaselines": {"config/detekt/baseline-sample.xml": {}},
        }

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_lines(self, path: pathlib.Path, count: int) -> None:
        path.write_text("\n".join(f"line {index}" for index in range(count)) + "\n", encoding="utf-8")

    def write_baseline(self, identifiers: list[str]) -> None:
        issues = "\n".join(f"    <ID>{identifier}</ID>" for identifier in identifiers)
        self.baseline.write_text(
            "<?xml version=\"1.0\" ?>\n"
            "<SmellBaseline>\n"
            "  <ManuallySuppressedIssues/>\n"
            f"  <CurrentIssues>\n{issues}\n  </CurrentIssues>\n"
            "</SmellBaseline>\n",
            encoding="utf-8",
        )

    def audit(self, base: str | None = None) -> dict[str, object]:
        return hotspots.audit(self.root, self.config, base)

    def test_accepts_sources_and_exact_zero_debt(self) -> None:
        self.assertEqual("passed", self.audit()["status"])

    def test_rejects_missing_or_malformed_configuration(self) -> None:
        with self.assertRaises(hotspots.ConfigurationError):
            hotspots.read_json(self.root / "missing.json")
        malformed = self.root / "malformed.json"
        malformed.write_text("{", encoding="utf-8")
        with self.assertRaises(hotspots.ConfigurationError):
            hotspots.read_json(malformed)
        with self.assertRaises(hotspots.ConfigurationError):
            hotspots.validate_config({})

    def test_rejects_new_production_hotspot(self) -> None:
        self.write_lines(self.production, 6)
        report = self.audit()
        self.assertIn("unbudgeted production hotspot", "\n".join(report["errors"]))

    def test_rejects_growth_move_and_stale_exception(self) -> None:
        path = self.production.relative_to(self.root).as_posix()
        self.config["lineBudgets"]["productionExceptions"] = {path: 7}
        self.write_lines(self.production, 8)
        self.assertIn("production hotspot grew", "\n".join(self.audit()["errors"]))
        self.production.unlink()
        self.assertIn("missing or moved", "\n".join(self.audit()["errors"]))
        self.production.write_text("package sample\n", encoding="utf-8")
        self.assertIn("stale production hotspot", "\n".join(self.audit()["errors"]))

    def test_rejects_oversized_test_without_exceptions(self) -> None:
        self.write_lines(self.test, 7)
        self.assertIn("oversized test file", "\n".join(self.audit()["errors"]))

    def test_non_test_source_sets_are_production(self) -> None:
        benchmark_source = self.module / "src/benchmarkRelease/kotlin/Fixture.kt"
        benchmark_source.parent.mkdir(parents=True)
        self.write_lines(benchmark_source, 6)
        self.assertIn("unbudgeted production hotspot", "\n".join(self.audit()["errors"]))

    def test_rejects_undeclared_kotlin_module(self) -> None:
        omitted = self.root / "omitted"
        (omitted / "build.gradle.kts").parent.mkdir(parents=True)
        (omitted / "build.gradle.kts").write_text("plugins {}\n", encoding="utf-8")
        source = omitted / "src/main/kotlin/Omitted.kt"
        source.parent.mkdir(parents=True)
        source.write_text("package omitted\n", encoding="utf-8")
        with self.assertRaisesRegex(hotspots.ConfigurationError, "undeclared Kotlin modules"):
            self.audit()

    def test_rejects_unconfigured_detekt_baseline(self) -> None:
        extra = self.root / "config/detekt/baseline-extra.xml"
        extra.write_text(self.baseline.read_text(encoding="utf-8"), encoding="utf-8")
        with self.assertRaisesRegex(hotspots.ConfigurationError, "unconfigured baseline files"):
            self.audit()

    def test_rejects_detekt_rule_count_or_unknown_rule(self) -> None:
        self.write_baseline(["LongMethod:Feature.kt:fun long"])
        self.assertIn("baseline debt changed", "\n".join(self.audit()["errors"]))
        self.config["detektBaselines"]["config/detekt/baseline-sample.xml"] = {"LongMethod": 1}
        self.assertEqual("passed", self.audit()["status"])

    def test_rejects_new_detekt_id_even_when_rule_count_is_unchanged(self) -> None:
        subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.name", "Test"], cwd=self.root, check=True)
        self.write_baseline(["LongMethod:Old.kt:fun old"])
        self.config["detektBaselines"]["config/detekt/baseline-sample.xml"] = {"LongMethod": 1}
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(["git", "commit", "-qm", "baseline"], cwd=self.root, check=True)
        self.write_baseline(["LongMethod:New.kt:fun new"])
        report = self.audit("HEAD")
        self.assertIn("gained IDs", "\n".join(report["errors"]))

    def test_repository_quality_lane_and_workflow_own_the_gate(self) -> None:
        package = json.loads((REPOSITORY_ROOT / "package.json").read_text(encoding="utf-8"))
        workflow = (REPOSITORY_ROOT / ".github/workflows/verify.yml").read_text(encoding="utf-8")
        duplication = json.loads((REPOSITORY_ROOT / ".jscpd.json").read_text(encoding="utf-8"))
        self.assertIn("check_hotspots.py", package["scripts"]["audit:hotspots"])
        self.assertIn("audit:hotspots", package["scripts"]["audit:quality"])
        self.assertIn("scripts/check_hotspots.py", workflow)
        self.assertIn("build/reports/quality/", workflow)
        self.assertLessEqual(duplication["threshold"], 0.6)
        self.assertIn("**/src/test/**", duplication["ignore"])


if __name__ == "__main__":
    unittest.main()
