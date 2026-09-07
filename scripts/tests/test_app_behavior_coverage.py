from __future__ import annotations

import contextlib
import io
from pathlib import Path, PurePosixPath
import sys
import subprocess
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_changed_coverage as coverage


class AppBehaviorCoverageTest(unittest.TestCase):
    def test_app_workflow_is_counted_without_counting_its_screen_or_test(self) -> None:
        owner = PurePosixPath("app/src/main/java/com/example/deep/SearchViewModel.kt")
        screen = PurePosixPath("app/src/main/java/com/example/SearchScreen.kt")
        test = PurePosixPath("app/src/test/java/com/example/SearchViewModel.kt")
        domain = PurePosixPath("core-domain/src/main/kotlin/com/example/Query.kt")
        changes = {path: {3} for path in [owner, screen, test, domain]}
        selected = coverage.production_changed_lines(
            changes, frozenset({"core-domain"}), ["app/src/main/**/*ViewModel.kt"],
        )
        self.assertEqual({owner: {3}, domain: {3}}, selected)
        self.assertEqual(
            {owner: {3}},
            coverage.production_changed_lines(changes, included_paths=["app/src/main/**/*ViewModel.kt"]),
        )

    def test_app_workflow_uncovered_line_is_a_failure_and_missing_report_is_an_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = PurePosixPath("app/src/main/java/com/example/SaveWorkflow.kt")
            source = root / path
            source.parent.mkdir(parents=True)
            source.write_text("package com.example\nclass SaveWorkflow { fun save() = Unit }\n")
            arguments = dict(
                repo=root, changed={path: {2}}, included_modules=frozenset({"core-domain"}),
                included_paths=["app/src/main/**/*Workflow.kt"],
            )
            result = coverage.calculate_changed_coverage(
                report={("com/example", "SaveWorkflow.kt"): {2: False}}, **arguments,
            )
            self.assertEqual(0, result.percentage)
            self.assertEqual(((path, 2),), result.uncovered_locations)
            with self.assertRaisesRegex(coverage.CoverageCheckError, "no source entry"):
                coverage.calculate_changed_coverage(report={}, **arguments)

    def test_invalid_scope_fails_instead_of_silently_skipping_coverage(self) -> None:
        for pattern in ["", "/app/*.kt", "../app/*.kt", "app/../*.kt", "app\\*.kt"]:
            with self.subTest(pattern=pattern), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    coverage.parse_args(["--xml", "report.xml", "--include-path", pattern])

    def test_local_precommit_check_includes_an_untracked_owner_but_not_test_fixtures(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "--quiet", str(root)], check=True, capture_output=True)
            owner = PurePosixPath("app/src/main/java/com/example/NewWorkflow.kt")
            test = PurePosixPath("app/src/test/java/com/example/NewWorkflowTest.kt")
            for path in [owner, test]:
                source = root / path
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("package com.example\nclass Feature\n")
            self.assertEqual({owner: {1, 2}}, coverage.untracked_production_lines(root))


if __name__ == "__main__":
    unittest.main()
