from __future__ import annotations

import os
import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPTS_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import check_adrs  # noqa: E402


class RepositoryTest(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = pathlib.Path(directory.name)
        self.write("core/src/a/Search.kt", "class SearchModel {\n    fun run() = Unit\n}\n")
        self.write("core/src/b/Other.kt", "val otherValue = 1\n")

    def write(self, path: str, text: str) -> None:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def record(self, name: str, applies: str, status: str = "Accepted", guarded: str | None = "- Left to prose: none.") -> None:
        guards = "" if guarded is None else f"\n## Guarded by\n\n{guarded}\n"
        self.write(f"docs/adr/{name}", f"# Title\n\nStatus: {status}\n\n## Applies to\n\n{applies}\n{guards}")

    def problems(self, moved: dict[str, str] | None = None, deleted: set[str] | None = None, tests: list[str] | None = None) -> list[str]:
        return check_adrs.check(self.root, moved, deleted, tests)


class CheckTest(RepositoryTest):
    def test_accepts_paths_symbols_members_and_sibling_paths(self) -> None:
        self.record(
            "0001-a.md",
            "- `core/src/a/Search.kt`, `b/Other.kt`: `SearchModel.run`, `otherValue`, the prose `run` call\n"
            "  `SearchModel`",
        )
        self.assertEqual([], self.problems())

    def test_reports_missing_path(self) -> None:
        self.record("0001-a.md", "- `core/src/a/Gone.kt`: `SearchModel`")
        self.assertEqual(1, len(problems := self.problems()))
        self.assertIn("`core/src/a/Gone.kt` does not exist", problems[0])

    def test_reports_symbol_missing_from_every_file_on_its_line(self) -> None:
        self.record("0001-a.md", "- `core/src/a/Search.kt`: `SearchModel.stop`")
        self.assertEqual(
            ["docs/adr/0001-a.md: `SearchModel.stop` is not in `core/src/a/Search.kt`. "
             "Rename it here or move it to the file that now holds it."],
            self.problems(),
        )

    def test_skips_superseded_records(self) -> None:
        self.record("0001-a.md", "- `core/src/a/Gone.kt`: `Nothing`", status="Superseded by [0002](0002-b.md)")
        self.assertEqual([], self.problems())

    def test_names_new_path_of_moved_file_and_flags_deleted_file(self) -> None:
        self.record("0001-a.md", "- `core/src/a/Search.kt`, `Old.kt`: `SearchModel`")
        self.record("0002-b.md", "- `gradlew`")
        problems = self.problems({"core/src/a/Old.kt": "core/src/a/Find.kt"}, {"gradlew"})
        self.assertEqual("  It moved to `core/src/a/Find.kt` in this change; point the record there.", problems[0].splitlines()[1])
        self.assertIn("`gradlew` does not exist", problems[1])
        self.assertIn("It was deleted in this change", problems[1])

    def test_sibling_of_a_moved_path_resolves_against_its_old_directory(self) -> None:
        self.record("0001-a.md", "- `core/src/old/First.kt`, `Second.kt`")
        moved = {"core/src/old/First.kt": "core/src/new/First.kt", "core/src/old/Second.kt": "core/src/new/Second.kt"}
        problems = self.problems(moved)
        self.assertIn("It moved to `core/src/new/Second.kt`", problems[1])

    def test_existing_sibling_after_a_moved_sibling_still_resolves(self) -> None:
        self.write("core/src/a/Third.kt", "")
        self.record("0001-a.md", "- `core/src/a/Search.kt`, `Second.kt`, `Third.kt`")
        problems = self.problems({"core/src/a/Second.kt": "core/src/z/Second.kt"})
        self.assertEqual(1, len(problems))
        self.assertIn("It moved to `core/src/z/Second.kt`", problems[0])

    def test_directory_stands_for_every_file_under_it(self) -> None:
        self.record("0001-a.md", "- `core/src/`: `SearchModel`, `otherValue`\n- `core/src/b/`: `SearchModel`")
        self.assertEqual(["docs/adr/0001-a.md: `SearchModel` is not in `core/src/b/`. "
                          "Rename it here or move it to the file that now holds it."], self.problems())


KOTLIN_TEST = "core/src/test/a/SearchTest.kt"


class GuardedByTest(RepositoryTest):
    def setUp(self) -> None:
        super().setUp()
        self.write(
            KOTLIN_TEST,
            "class SearchTest {\n"
            "    @Test\n"
            "    fun `search runs once (ADR 0001)`() {\n"
            "        assertEquals(1, 1)\n"
            "    }\n"
            "\n"
            "    @Test fun `search runs untagged`() = Unit\n"
            "}\n",
        )
        self.write("scripts/tests/test_tool.py", "class ToolTest:\n    def test_tool_works_adr_0001(self):\n        pass\n")

    def guarded(self, guards: str, tests: list[str] | None = None) -> list[str]:
        self.record("0001-a.md", "- `core/src/a/Search.kt`: `SearchModel`", guarded=guards)
        return self.problems(tests=tests)

    def test_accepts_tests_sources_signatures_and_prose(self) -> None:
        self.write("core/src/a/Api.kt", "interface Api {\n    suspend fun post(\n        id: String,\n    ): Post?\n}\n")
        self.assertEqual([], self.guarded(
            f"- `{KOTLIN_TEST}`: `search runs once (ADR 0001)`\n"
            "- `scripts/tests/test_tool.py`: `test_tool_works_adr_0001`\n"
            "- `core/src/a/Search.kt`: `SearchModel.run`\n"
            "- `core/src/a/Api.kt`: `suspend fun post(id: String): Post?`\n"
            "- Left to prose: the `debounce` length is a feel judgment.",
            [KOTLIN_TEST, "scripts/tests/test_tool.py"],
        ))

    def test_reports_a_test_that_is_missing_or_declared_twice(self) -> None:
        self.write("core/src/test/a/TwiceTest.kt", "@Test\nfun `twice (ADR 0001)`() = Unit\n@Test\nfun `twice (ADR 0001)`() = Unit\n")
        problems = self.guarded(f"- `{KOTLIN_TEST}`: `search stops (ADR 0001)`\n- `core/src/test/a/TwiceTest.kt`: `twice (ADR 0001)`")
        self.assertIn("guard `search stops (ADR 0001)` is not a test", problems[0])
        self.assertIn("is declared more than once", problems[1])

    def test_reports_a_test_that_does_not_run(self) -> None:
        self.write("core/src/test/a/BrokenTest.kt", "    @Test\n    @Ignore(\"flaky\")\n    fun `ignored (ADR 0001)`() = Unit\n\n    fun `helper (ADR 0001)`() = Unit\n")
        self.write("scripts/tests/test_other.py", "def helper_adr_0001():\n    pass\n")
        problems = self.guarded(
            "- `core/src/test/a/BrokenTest.kt`: `ignored (ADR 0001)`, `helper (ADR 0001)`\n"
            "- `scripts/tests/test_other.py`: `helper_adr_0001`"
        )
        self.assertEqual(3, len(problems))
        self.assertTrue(all("does not run" in problem for problem in problems), problems)

    def test_accepts_a_multi_line_test_annotation(self) -> None:
        lines = ["class SlowTest {", "    @Test(", "        timeout = 5_000L,", "    )", "    fun `slow`() = Unit", "}"]
        self.assertTrue(check_adrs.runs(pathlib.Path("SlowTest.kt"), lines, 4, "slow"))

    def test_rejects_ignored_expected_and_skipped_tests(self) -> None:
        kotlin = {
            "class": ["@Ignore", "class IgnoredTest {", "    @Test", "    fun `x`() = Unit", "}"],
            "blank line": ["class IgnoredTest {", "    @Ignore", "", "    @Test", "    fun `x`() = Unit", "}"],
            "expected": ["class IgnoredTest {", "    @Test(expected = IllegalStateException::class)", "    fun `x`() = Unit", "}"],
        }
        for case, lines in kotlin.items():
            self.assertFalse(check_adrs.runs(pathlib.Path("IgnoredTest.kt"), lines, len(lines) - 2, "x"), case)
        python = {
            "skip": ["class ToolTest(unittest.TestCase):", "    @unittest.skip('later')", "    def test_x(self):", "        pass"],
            "skipped class": ["@skipIf(True, 'no')", "class ToolTest(unittest.TestCase):", "    def test_x(self):", "        pass"],
            "module level": ["def test_x():", "    pass"],
        }
        for case, lines in python.items():
            self.assertFalse(check_adrs.runs(pathlib.Path("test_tool.py"), lines, len(lines) - 2, "test_x"), case)

    def test_reports_a_guard_without_this_records_tag(self) -> None:
        self.record("0002-b.md", "- `core/src/a/Search.kt`", guarded=f"- `{KOTLIN_TEST}`: `search runs once (ADR 0001)`")
        problems = self.problems()
        self.assertEqual(1, len(problems))
        self.assertIn("docs/adr/0002-b.md: guard `search runs once (ADR 0001)` does not carry this record's tag; end its name with (ADR 0002)", problems[0])

    def test_reads_every_tag_a_name_carries(self) -> None:
        self.assertEqual(["0008", "0022"], check_adrs.tags("keeps owners (ADR 0008, 0022)"))
        self.assertEqual(["0008", "0022"], check_adrs.tags("test_keeps_owners_adr_0008_adr_0022"))
        self.assertEqual([], check_adrs.tags("keeps owners (ADR 0008) when idle"))

    def test_pinned_source_text_matches_whole_tokens_from_the_start_of_a_line(self) -> None:
        def pinned(pin: str, text: str) -> bool:
            return check_adrs.pinned(pin, text, python=False)

        self.assertFalse(pinned("fun post(): Post", "fun post(): Post?\n"))
        self.assertFalse(pinned("fun posts(): List<Post>", "fun posts(): List<Post>?\n"))
        self.assertFalse(pinned("internal class Foo", "internal class FooImpl\n"))
        self.assertFalse(pinned("fun x()", "    private fun x() = Unit\n"))
        self.assertFalse(pinned("fun f()", "fun f(): Int = 1\n"))
        self.assertTrue(pinned("suspend fun post(id: String): Post?", "    suspend fun post(\n        id: String,\n    ): Post? {\n"))
        self.assertTrue(check_adrs.pinned("@rule def run() -> list[str]", "@rule\ndef run() -> list[str]:\n", python=True))

    def test_reports_a_changed_signature(self) -> None:
        self.assertEqual(
            ["docs/adr/0001-a.md: `fun run(): Int` is not in `core/src/a/Search.kt`; the signature this record pins has "
             "changed: restore it, or edit the record if the decision changed."],
            self.guarded("- `core/src/a/Search.kt`: `fun run(): Int`"),
        )

    def test_reports_directories_mixed_lines_and_empty_sections(self) -> None:
        self.assertIn("is a directory", self.guarded("- `core/src/a/`: `SearchModel`")[0])
        self.assertIn("mixes test and source files", self.guarded(f"- `{KOTLIN_TEST}`, `core/src/a/Search.kt`: `SearchModel`")[0])
        self.assertIn("\"Guarded by\" lists nothing", self.guarded("")[0])

    def test_reports_a_guard_line_without_a_backticked_path(self) -> None:
        self.assertIn("names no backticked path", self.guarded("- core/src/a/Search.kt: `SearchModel`")[0])

    def test_reports_a_guard_line_without_a_guard(self) -> None:
        self.assertIn("names no guard", self.guarded("- `core/src/a/Search.kt`")[0])
        self.assertIn("gives no reason", self.guarded("- Left to prose:")[0])

    def test_reports_a_tagged_test_name_on_a_source_file(self) -> None:
        self.assertIn("is a tagged test name in a file that is not a test source set", self.guarded("- `core/src/a/Search.kt`: `run (ADR 0001)`")[0])

    def test_test_files_include_every_test_source_set(self) -> None:
        for path in ["app/src/testDebug/kotlin/A.kt", "core-data/src/testFixtures/kotlin/B.kt", "app/src/sharedTest/java/C.java", "app/src/androidTest/D.kt"]:
            self.assertIsNotNone(check_adrs.TEST_FILE.fullmatch(path), path)
        self.assertIsNone(check_adrs.TEST_FILE.fullmatch("macrobenchmark/src/main/kotlin/E.kt"))

    def test_names_new_path_of_a_moved_guard_once(self) -> None:
        self.record("0001-a.md", "- `core/src/test/a/OldTest.kt`", guarded="- `core/src/test/a/OldTest.kt`: `x (ADR 0001)`")
        problems = self.problems({"core/src/test/a/OldTest.kt": "core/src/test/a/NewTest.kt"})
        self.assertEqual(1, len(problems))
        self.assertIn("It moved to `core/src/test/a/NewTest.kt`", problems[0])

    def test_reports_tagged_tests_their_record_does_not_list(self) -> None:
        self.record("0002-b.md", "- `core/src/a/Gone.kt`", status="Superseded")
        self.write("core/src/test/a/StaleTest.kt", "@Test\nfun `stale (ADR 0002)`() = Unit\n@Test\nfun `unknown (ADR 0009)`() = Unit\n")
        problems = self.guarded("- Left to prose: nothing to check.", [KOTLIN_TEST, "core/src/test/a/StaleTest.kt"])
        self.assertEqual(3, len(problems))
        self.assertIn(f"{KOTLIN_TEST}: test `search runs once (ADR 0001)` carries the tag of docs/adr/0001-a.md, which does not list it", problems[0])
        self.assertIn("test `stale (ADR 0002)` guards a superseded record (ADR 0002)", problems[1])
        self.assertIn("test `unknown (ADR 0009)` carries the tag of ADR 0009, which does not exist", problems[2])

    def test_reports_malformed_tags(self) -> None:
        self.write("core/src/test/a/OddTest.kt", "@Test\nfun `odd (ADR 22)`() = Unit\n@Test\nfun `odd (adr 0001)`() = Unit\n@Test\nfun oddName_adr_1() = Unit\n")
        problems = self.guarded("- Left to prose: nothing to check.", ["core/src/test/a/OddTest.kt"])
        self.assertEqual(3, len(problems))
        self.assertTrue(all("malformed ADR tag" in problem for problem in problems), problems)


class UnguardedTest(RepositoryTest):
    def unguarded(self, *names: str) -> None:
        self.write("docs/adr/unguarded.txt", "".join(f"{name}\n" for name in names))

    def test_accepts_a_listed_record_without_guards(self) -> None:
        self.record("0001-a.md", "- `core/src/a/Search.kt`", guarded=None)
        self.unguarded("0001-a.md")
        self.assertEqual([], self.problems())

    def test_reports_an_unlisted_record_without_guards(self) -> None:
        self.record("0001-a.md", "- `core/src/a/Search.kt`", guarded=None)
        self.assertEqual(
            ["docs/adr/0001-a.md: add a \"Guarded by\" section naming a guard, or a `Left to prose:` line saying why none is possible; see docs/adr/README.md."],
            self.problems(),
        )

    def test_reports_a_list_that_did_not_shrink(self) -> None:
        self.record("0001-a.md", "- `core/src/a/Search.kt`")
        self.record("0002-b.md", "- `core/src/a/Search.kt`", guarded=None)
        self.unguarded("0002-b.md", "0001-a.md", "0003-gone.md")
        self.assertEqual(
            ["docs/adr/unguarded.txt: names are not sorted; sort them.",
             "docs/adr/0001-a.md has a \"Guarded by\" section; remove it from docs/adr/unguarded.txt.",
             "docs/adr/unguarded.txt: `0003-gone.md` is not a live record; remove it."],
            self.problems(),
        )


class GuardEditTest(unittest.TestCase):
    OLD = (
        "    @Test\n"
        "    fun `keeps owners (ADR 0001)`() {\n"
        "        val owners = load()\n"
        "        assertEquals(2, owners.size)\n"
        "    }\n"
        "\n"
        "    @Test\n"
        "    fun `other`() {\n"
        "        assertTrue(true)\n"
        "    }\n"
    )

    def test_span_runs_to_the_closing_brace(self) -> None:
        lines = self.OLD.splitlines()
        self.assertEqual(lines[1:5], check_adrs.test_span(lines, 1))
        self.assertEqual(["def test_a(self):", "    self.assertTrue(x)"], check_adrs.test_span(["def test_a(self):", "    self.assertTrue(x)", "def test_b(self):"], 0))

    def test_flags_any_edit_to_the_guard_body_but_not_formatting_or_other_tests(self) -> None:
        name = "keeps owners (ADR 0001)"
        self.assertTrue(check_adrs.guard_edited(self.OLD, self.OLD.replace("assertEquals(2", "assertEquals(3"), name))
        self.assertTrue(check_adrs.guard_edited(self.OLD, self.OLD.replace("load()", "load(cached = true)"), name))
        self.assertFalse(check_adrs.guard_edited(self.OLD, self.OLD.replace("        val owners", "\n            val owners"), name))
        self.assertFalse(check_adrs.guard_edited(self.OLD, self.OLD.replace("assertTrue(true)", "assertTrue(false)"), name))
        self.assertFalse(check_adrs.guard_edited(self.OLD.replace("keeps owners", "keeps"), self.OLD, name))


class ChangesTest(RepositoryTest):
    def git(self, *args: str) -> None:
        subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)

    def test_finds_renames_and_deletions_against_a_revision(self) -> None:
        self.git("init", "-q")
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "add", ".")
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "base")
        self.git("mv", "core/src/a/Search.kt", "core/src/a/Find.kt")
        self.git("rm", "-q", "core/src/b/Other.kt")
        previous = os.getcwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, previous)
        self.assertEqual(({"core/src/a/Search.kt": "core/src/a/Find.kt"}, {"core/src/b/Other.kt"}), check_adrs.changes("HEAD"))


class NotesTest(RepositoryTest):
    def commit(self) -> None:
        for args in (["init", "-q"], ["add", "."], ["-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "base"]):
            subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)

    def test_notes_name_records_whose_symbols_the_change_touches_and_guards_edited_beside_their_code(self) -> None:
        guard = "    @Test\n    fun `runs (ADR 0001)`() {\n        assertEquals(1, run())\n    }\n"
        self.write("core/src/test/a/SearchTest.kt", guard)
        self.record("0001-a.md", "- `core/src/a/`: `SearchModel.run`", guarded="- `core/src/test/a/SearchTest.kt`: `runs (ADR 0001)`")
        self.record("0002-b.md", "- `core/src/b/Other.kt`")
        self.record("0003-c.md", "- `core/src/a/Search.kt`: `SearchModel`")
        self.record("0004-d.md", "- `core/src/b/`: `otherValue`")
        self.commit()
        self.write("core/src/a/Search.kt", "class SearchModel {\n    fun run() = 2\n}\n")
        self.write("core/src/b/New.kt", "val newValue = 1\n")
        subprocess.run(["git", "add", "core/src/b/New.kt"], cwd=self.root, check=True)
        self.write("core/src/test/a/SearchTest.kt", guard.replace("assertEquals(1", "assertEquals(2"))

        self.assertEqual(["  note: this change edits code governed by docs/adr/0001-a.md, docs/adr/0004-d.md; reread them."], check_adrs.governing_notes(self.root, "HEAD"))
        self.assertEqual(
            ["  note: docs/adr/0001-a.md: this change edits guard `runs (ADR 0001)` and code the record governs (core/src/a/Search.kt). "
             "If the promise still holds, restore the guard; if the decision changed, edit the record."],
            check_adrs.guard_edit_notes(self.root, "HEAD"),
        )

        self.record("0001-a.md", "- `core/src/a/`: `SearchModel`, `run`", guarded="- `core/src/test/a/SearchTest.kt`: `runs (ADR 0001)`")
        self.record("0004-d.md", "- `core/src/b/`")
        self.assertEqual(([], []), (check_adrs.governing_notes(self.root, "HEAD"), check_adrs.guard_edit_notes(self.root, "HEAD")))

    def test_governing_note_lists_at_most_eight_records(self) -> None:
        for number in range(1, 11):
            self.record(f"{number:04d}-r.md", "- `core/src/b/Other.kt`")
        self.commit()
        self.write("core/src/b/Other.kt", "val otherValue = 2\n")
        note = check_adrs.governing_notes(self.root, "HEAD")[0]
        self.assertTrue(note.endswith("docs/adr/0008-r.md and 2 more; reread them."), note)


if __name__ == "__main__":
    unittest.main()
