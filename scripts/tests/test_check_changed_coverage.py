from __future__ import annotations

import contextlib
import io
import pathlib
import sys
import tempfile
import textwrap
import unittest
from unittest import mock


SCRIPTS_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import check_changed_coverage as coverage  # noqa: E402


class ChangedCoverageTest(unittest.TestCase):
    def test_ownership_manifest_protects_engines_controllers_queues_and_future_helpers(self) -> None:
        repo = SCRIPTS_DIR.parent
        args = coverage.parse_args(["--xml", "unused.xml", "--repo", str(repo), "--boundaries", "config/quality/coverage-boundaries.json"])
        protected = [
            "viewer/FeedPreviewPlayerPool.kt", "media/MediaDurationAcquisitionEngine.kt",
            "update/ApkDownloadManager.kt", "update/ApkUpdateValidator.kt",
            "media/PostDownloadQueue.kt", "sourceauth/PixivPkceController.kt", "media/FutureTransportHelper.kt",
        ]
        prefix = "app/src/main/java/com/theoriacodex/app/"
        paths = [pathlib.PurePosixPath(prefix + path) for path in protected]
        ui = pathlib.PurePosixPath(prefix + "search/SearchScreen.kt")
        changed = {path: {1} for path in paths + [ui]}
        result = coverage.production_changed_lines(changed, frozenset(args.include_module), args.include_path, args.exclude_path)
        self.assertEqual(set(paths), set(result))

    def test_parses_jacoco_source_lines(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            xml_path = pathlib.Path(temporary_directory) / "coverage.xml"
            xml_path.write_text(
                textwrap.dedent(
                    """\
                    <?xml version="1.0" encoding="UTF-8"?>
                    <report name="test">
                      <package name="com/example/domain">
                        <sourcefile name="Feature.kt">
                          <line nr="10" mi="0" ci="3" mb="0" cb="0"/>
                          <line nr="11" mi="2" ci="0" mb="0" cb="0"/>
                        </sourcefile>
                      </package>
                    </report>
                    """
                ),
                encoding="utf-8",
            )

            parsed = coverage.parse_coverage_xml(xml_path)

        self.assertEqual(
            {10: True, 11: False},
            parsed[("com/example/domain", "Feature.kt")],
        )

    def test_parses_new_line_ranges_from_zero_context_diff(self) -> None:
        changed = coverage.parse_git_diff(
            textwrap.dedent(
                """\
                diff --git a/app/src/main/java/com/example/Feature.kt b/app/src/main/java/com/example/Feature.kt
                --- a/app/src/main/java/com/example/Feature.kt
                +++ b/app/src/main/java/com/example/Feature.kt
                @@ -8,0 +9,2 @@
                +first
                +second
                @@ -20 +22 @@
                -old
                +new
                diff --git a/app/src/main/java/com/example/Removed.kt b/app/src/main/java/com/example/Removed.kt
                --- a/app/src/main/java/com/example/Removed.kt
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -gone
                """
            )
        )

        self.assertEqual(
            {9, 10, 22},
            changed[pathlib.PurePosixPath("app/src/main/java/com/example/Feature.kt")],
        )
        self.assertNotIn(
            pathlib.PurePosixPath("app/src/main/java/com/example/Removed.kt"),
            changed,
        )

    def test_filters_non_production_and_baseline_profile_sources(self) -> None:
        candidates = {
            pathlib.PurePosixPath("app/src/main/java/com/example/App.kt"): {1},
            pathlib.PurePosixPath("app/src/test/java/com/example/AppTest.kt"): {1},
            pathlib.PurePosixPath("app/src/androidTest/java/com/example/AppTest.kt"): {1},
            pathlib.PurePosixPath("app/build/generated/source/Generated.kt"): {1},
            pathlib.PurePosixPath("baseline-profile/src/main/java/Profile.kt"): {1},
            pathlib.PurePosixPath("scripts/tests/helper.kt"): {1},
        }

        filtered = coverage.production_changed_lines(candidates)

        self.assertEqual(
            {pathlib.PurePosixPath("app/src/main/java/com/example/App.kt"): {1}},
            filtered,
        )

    def test_filters_to_explicitly_included_modules(self) -> None:
        candidates = {
            pathlib.PurePosixPath("app/src/main/java/com/example/App.kt"): {1},
            pathlib.PurePosixPath("core-domain/src/main/kotlin/com/example/Domain.kt"): {2},
            pathlib.PurePosixPath("core-sources/src/main/kotlin/com/example/Source.kt"): {3},
        }

        filtered = coverage.production_changed_lines(
            candidates,
            included_modules=frozenset({"core-domain", "core-sources"}),
        )

        self.assertEqual(
            {
                pathlib.PurePosixPath(
                    "core-domain/src/main/kotlin/com/example/Domain.kt"
                ): {2},
                pathlib.PurePosixPath(
                    "core-sources/src/main/kotlin/com/example/Source.kt"
                ): {3},
            },
            filtered,
        )

    def test_app_logic_changed_source_fails_closed_when_report_omits_it(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo = pathlib.Path(temporary_directory)
            source_path = pathlib.PurePosixPath(
                "app-logic/src/main/kotlin/com/example/AppPolicy.kt"
            )
            self._write_source(repo, source_path, "com.example")

            with self.assertRaisesRegex(
                coverage.CoverageCheckError,
                "coverage XML has no source entry",
            ):
                coverage.calculate_changed_coverage(
                    repo=repo,
                    changed={source_path: {2}},
                    report={},
                    included_modules=frozenset({"app-logic"}),
                )

    def test_declarations_only_file_in_a_reported_module_has_no_executable_lines(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo = pathlib.Path(temporary_directory)
            covered = pathlib.PurePosixPath("ui-kit/src/main/kotlin/com/example/Covered.kt")
            contract = pathlib.PurePosixPath("ui-kit/src/main/kotlin/com/example/state/Contract.kt")
            self._write_source(repo, covered, "com.example")
            self._write_source(repo, contract, "com.example.state")
            repo.joinpath(*contract.parts).write_text(
                "package com.example.state\n\n"
                "/** Reads `a = b` in a comment, which is not code. */\n"
                "interface Contract {\n    fun load(id: String): String\n}\n\n"
                "const val CONTRACT_VERSION = 2\n"
                "typealias ContractId = String\n",
                encoding="utf-8",
            )

            result = coverage.calculate_changed_coverage(
                repo=repo,
                changed={contract: {3}},
                report={("com/example", "Covered.kt"): {3: True}},
                included_paths=["*/src/main/**"],
            )

        self.assertEqual(1, result.changed_production_lines)
        self.assertEqual(0, result.executable_lines)

    def test_only_declaration_forms_count_as_declarations_only(self) -> None:
        declarations = (
            "package a.b\n\nimport a.c.Thing\n\n@Stable\ninterface Owner : Base<Thing> {\n"
            "    val thing: Thing\n    suspend fun load(\n        id: String,\n        retry: (Int) -> Boolean,\n"
            "    ): Thing?\n}\n\ninternal const val LIMIT = 12\nconst val NAME = \"a = b\"\ntypealias Id = String\n"
        )
        self.assertTrue(coverage._declares_only(declarations))
        for code in (
            "class Stamp : java.util.Date(System.currentTimeMillis())",
            "object Registry",
            "enum class Mode { A, B }",
            "val limit = compute()",
            "val limit: Int get() = 3",
            "fun load(id: String): String = id",
            "fun load(id: String) {\n}",
            "interface Owner {\n    fun load(id: String = \"\"): String\n}",
            "interface Owner {\n    fun load(): String { return \"\" }\n}",
            "abstract class Host : Activity()",
            "private val cache by lazy { build() }",
            "init { start() }",
        ):
            with self.subTest(code=code):
                self.assertFalse(coverage._declares_only("package a.b\n\n" + code + "\n"))

    def test_file_with_code_missing_from_a_reported_module_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo = pathlib.Path(temporary_directory)
            covered = pathlib.PurePosixPath("ui-kit/src/main/kotlin/com/example/Covered.kt")
            omitted = pathlib.PurePosixPath("ui-kit/src/main/kotlin/com/example/state/Omitted.kt")
            self._write_source(repo, covered, "com.example")
            self._write_source(repo, omitted, "com.example.state")

            with self.assertRaisesRegex(coverage.CoverageCheckError, "coverage XML has no source entry"):
                coverage.calculate_changed_coverage(
                    repo=repo,
                    changed={omitted: {3}},
                    report={("com/example", "Covered.kt"): {3: True}},
                    included_paths=["*/src/main/**"],
                )

    def test_changed_file_in_a_module_the_report_omits_still_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo = pathlib.Path(temporary_directory)
            covered = pathlib.PurePosixPath("ui-kit/src/main/kotlin/com/example/Covered.kt")
            unreported = pathlib.PurePosixPath("media-kit/src/main/kotlin/com/example/media/Player.kt")
            self._write_source(repo, covered, "com.example")
            self._write_source(repo, unreported, "com.example.media")

            with self.assertRaisesRegex(coverage.CoverageCheckError, "coverage XML has no source entry"):
                coverage.calculate_changed_coverage(
                    repo=repo,
                    changed={unreported: {3}},
                    report={("com/example", "Covered.kt"): {3: True}},
                    included_paths=["*/src/main/**"],
                )

    def test_maps_package_entries_across_modules_and_counts_only_xml_lines(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo = pathlib.Path(temporary_directory)
            domain_path = pathlib.PurePosixPath(
                "core-domain/src/main/kotlin/com/example/domain/Feature.kt"
            )
            app_path = pathlib.PurePosixPath(
                "app/src/main/java/com/example/ui/Screen.kt"
            )
            self._write_source(repo, domain_path, "com.example.domain")
            self._write_source(repo, app_path, "com.example.ui")
            changed = {
                domain_path: {10, 11, 99},
                app_path: {20},
            }
            report = {
                ("com/example/domain", "Feature.kt"): {10: True, 11: False},
                ("com/example/ui", "Screen.kt"): {20: True},
            }

            result = coverage.calculate_changed_coverage(repo, changed, report)

        self.assertEqual(4, result.changed_production_lines)
        self.assertEqual(3, result.executable_lines)
        self.assertEqual(2, result.covered_lines)
        self.assertEqual(1, result.ignored_lines)
        self.assertEqual(
            ((domain_path, 11),),
            result.uncovered_locations,
        )

    def test_no_base_skips_before_requiring_xml(self) -> None:
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            result = coverage.main(["--xml", "does-not-exist.xml"])

        self.assertEqual(0, result)
        self.assertIn("no base revision", stdout.getvalue())

    def test_missing_xml_with_base_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                result = coverage.main(
                    [
                        "--xml",
                        "does-not-exist.xml",
                        "--base",
                        "origin/main",
                        "--repo",
                        temporary_directory,
                    ]
                )

        self.assertEqual(2, result)
        self.assertIn("coverage XML is missing", stderr.getvalue())

    def test_threshold_failure_lists_uncovered_changed_lines(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo = pathlib.Path(temporary_directory)
            source_path = pathlib.PurePosixPath(
                "app/src/main/java/com/example/Feature.kt"
            )
            self._write_source(repo, source_path, "com.example")
            xml_path = repo / "coverage.xml"
            xml_path.write_text(
                textwrap.dedent(
                    """\
                    <report name="test">
                      <package name="com/example">
                        <sourcefile name="Feature.kt">
                          <line nr="10" mi="0" ci="1"/>
                          <line nr="11" mi="1" ci="0"/>
                        </sourcefile>
                      </package>
                    </report>
                    """
                ),
                encoding="utf-8",
            )
            diff = textwrap.dedent(
                """\
                diff --git a/app/src/main/java/com/example/Feature.kt b/app/src/main/java/com/example/Feature.kt
                --- a/app/src/main/java/com/example/Feature.kt
                +++ b/app/src/main/java/com/example/Feature.kt
                @@ -9,0 +10,2 @@
                +covered
                +uncovered
                """
            )
            stderr = io.StringIO()
            with mock.patch.object(coverage, "git_diff_text", return_value=diff):
                with contextlib.redirect_stderr(stderr):
                    result = coverage.main(
                        [
                            "--xml",
                            str(xml_path),
                            "--base",
                            "origin/main",
                            "--minimum",
                            "60",
                            "--repo",
                            str(repo),
                        ]
                    )

        self.assertEqual(1, result)
        self.assertIn("50.00%", stderr.getvalue())
        self.assertIn(f"{source_path}:11", stderr.getvalue())

    def test_missing_changed_source_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo = pathlib.Path(temporary_directory)
            source_path = pathlib.PurePosixPath(
                "core-data/src/main/kotlin/com/example/Unaggregated.kt"
            )
            self._write_source(repo, source_path, "com.example")
            xml_path = repo / "coverage.xml"
            xml_path.write_text("<report name=\"empty\"/>", encoding="utf-8")
            diff = textwrap.dedent(
                """\
                diff --git a/core-data/src/main/kotlin/com/example/Unaggregated.kt b/core-data/src/main/kotlin/com/example/Unaggregated.kt
                --- a/core-data/src/main/kotlin/com/example/Unaggregated.kt
                +++ b/core-data/src/main/kotlin/com/example/Unaggregated.kt
                @@ -1,0 +2 @@
                +fun unaggregated() = Unit
                """
            )
            stderr = io.StringIO()
            with mock.patch.object(coverage, "git_diff_text", return_value=diff):
                with contextlib.redirect_stderr(stderr):
                    result = coverage.main(
                        [
                            "--xml",
                            str(xml_path),
                            "--base",
                            "origin/main",
                            "--include-module",
                            "core-data",
                            "--repo",
                            str(repo),
                        ]
                    )

        self.assertEqual(2, result)
        self.assertIn("no source entry", stderr.getvalue())
        self.assertIn(str(source_path), stderr.getvalue())

    def test_no_executable_changed_lines_skips_explicitly(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo = pathlib.Path(temporary_directory)
            source_path = pathlib.PurePosixPath(
                "core-data/src/main/kotlin/com/example/OnlyComment.kt"
            )
            self._write_source(repo, source_path, "com.example")
            xml_path = repo / "coverage.xml"
            xml_path.write_text(
                textwrap.dedent(
                    """\
                    <report name="test">
                      <package name="com/example">
                        <sourcefile name="OnlyComment.kt"/>
                      </package>
                    </report>
                    """
                ),
                encoding="utf-8",
            )
            diff = textwrap.dedent(
                """\
                diff --git a/core-data/src/main/kotlin/com/example/OnlyComment.kt b/core-data/src/main/kotlin/com/example/OnlyComment.kt
                --- a/core-data/src/main/kotlin/com/example/OnlyComment.kt
                +++ b/core-data/src/main/kotlin/com/example/OnlyComment.kt
                @@ -1,0 +2 @@
                +// Comment only
                """
            )
            stdout = io.StringIO()
            with mock.patch.object(coverage, "git_diff_text", return_value=diff):
                with contextlib.redirect_stdout(stdout):
                    result = coverage.main(
                        [
                            "--xml",
                            str(xml_path),
                            "--base",
                            "origin/main",
                            "--repo",
                            str(repo),
                        ]
                    )

        self.assertEqual(0, result)
        self.assertIn("no executable changed production Kotlin lines", stdout.getvalue())

    @staticmethod
    def _write_source(
        repo: pathlib.Path,
        relative_path: pathlib.PurePosixPath,
        package_name: str,
    ) -> None:
        source_path = repo.joinpath(*relative_path.parts)
        source_path.parent.mkdir(parents=True, exist_ok=True)
        source_path.write_text(
            f"package {package_name}\n\nfun covered() = Unit\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
