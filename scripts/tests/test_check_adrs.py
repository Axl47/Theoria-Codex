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

    def record(self, name: str, applies: str, status: str = "Accepted", guarded: str | None = None) -> None:
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

    def test_reports_a_guard_without_this_records_tag(self) -> None:
        self.record("0002-b.md", "- `core/src/a/Search.kt`", guarded=f"- `{KOTLIN_TEST}`: `search runs once (ADR 0001)`")
        problems = self.problems()
        self.assertEqual(1, len(problems))
        self.assertIn("docs/adr/0002-b.md: guard `search runs once (ADR 0001)` does not carry this record's tag; end its name with (ADR 0002)", problems[0])

    def test_reads_every_tag_a_name_carries(self) -> None:
        self.assertEqual(["0008", "0022"], check_adrs.tags("keeps owners (ADR 0008, 0022)"))
        self.assertEqual(["0008", "0022"], check_adrs.tags("test_keeps_owners_adr_0008_adr_0022"))
        self.assertEqual([], check_adrs.tags("keeps owners (ADR 0008) when idle"))

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

    def test_names_new_path_of_a_moved_guard(self) -> None:
        self.record("0001-a.md", "- `core/src/a/Search.kt`", guarded="- `core/src/test/a/OldTest.kt`: `x (ADR 0001)`")
        problems = self.problems({"core/src/test/a/OldTest.kt": "core/src/test/a/NewTest.kt"})
        self.assertIn("It moved to `core/src/test/a/NewTest.kt`", problems[0])

    def test_reports_tagged_tests_their_record_does_not_list(self) -> None:
        self.record("0002-b.md", "- `core/src/a/Gone.kt`", status="Superseded")
        self.write("core/src/test/a/StaleTest.kt", "@Test\nfun `stale (ADR 0002)`() = Unit\n@Test\nfun `unknown (ADR 0009)`() = Unit\n")
        problems = self.guarded("- Left to prose: nothing to check.", [KOTLIN_TEST, "core/src/test/a/StaleTest.kt"])
        self.assertEqual(3, len(problems))
        self.assertIn(f"{KOTLIN_TEST}: test `search runs once (ADR 0001)` carries the tag of docs/adr/0001-a.md, which does not list it", problems[0])
        self.assertIn("test `stale (ADR 0002)` guards a superseded record (ADR 0002)", problems[1])
        self.assertIn("test `unknown (ADR 0009)` carries the tag of ADR 0009, which does not exist", problems[2])


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

    def test_flags_an_edited_assertion_but_not_a_body_edit(self) -> None:
        name = "keeps owners (ADR 0001)"
        self.assertTrue(check_adrs.assertions_changed(self.OLD, self.OLD.replace("assertEquals(2", "assertEquals(3"), name))
        self.assertFalse(check_adrs.assertions_changed(self.OLD, self.OLD.replace("load()", "loadAll()"), name))
        self.assertFalse(check_adrs.assertions_changed(self.OLD, self.OLD.replace("assertTrue(true)", "assertTrue(false)"), name))
        self.assertFalse(check_adrs.assertions_changed(self.OLD.replace("keeps owners", "keeps"), self.OLD, name))


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
    def test_notes_name_governing_records_and_guards_edited_beside_their_code(self) -> None:
        guard = "    @Test\n    fun `runs (ADR 0001)`() {\n        assertEquals(1, run())\n    }\n"
        self.write("core/src/test/a/SearchTest.kt", guard)
        self.record("0001-a.md", "- `core/src/a/`: `SearchModel`", guarded="- `core/src/test/a/SearchTest.kt`: `runs (ADR 0001)`")
        self.record("0002-b.md", "- `core/src/b/Other.kt`")
        for args in (["init", "-q"], ["add", "."], ["-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "base"]):
            subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)
        self.write("core/src/a/Search.kt", "class SearchModel {\n    fun run() = 2\n}\n")
        self.write("core/src/test/a/SearchTest.kt", guard.replace("assertEquals(1", "assertEquals(2"))

        self.assertEqual(["  note: this change edits code governed by docs/adr/0001-a.md; reread them."], check_adrs.governing_notes(self.root, "HEAD"))
        notes = check_adrs.guard_edit_notes(self.root, "HEAD")
        self.assertEqual(1, len(notes))
        self.assertTrue(notes[0].startswith(
            "  note: docs/adr/0001-a.md: this change edits the assertions of guard `runs (ADR 0001)` and code the record governs (core/src/a/Search.kt)."
        ))

        self.record("0001-a.md", "- `core/src/a/`: `SearchModel`, `run`", guarded="- `core/src/test/a/SearchTest.kt`: `runs (ADR 0001)`")
        self.assertEqual(([], []), (check_adrs.governing_notes(self.root, "HEAD"), check_adrs.guard_edit_notes(self.root, "HEAD")))


if __name__ == "__main__":
    unittest.main()
