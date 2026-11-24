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

    def record(self, name: str, applies: str, status: str = "Accepted") -> None:
        self.write(f"docs/adr/{name}", f"# Title\n\nStatus: {status}\n\n## Applies to\n\n{applies}\n")

    def problems(self, moved: dict[str, str] | None = None, deleted: set[str] | None = None) -> list[str]:
        return check_adrs.check(self.root, moved, deleted)


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

    def test_reports_duplicate_numbers_with_the_numbering_rule(self) -> None:
        self.record("0007-a.md", "- `core/src/a/Search.kt`")
        self.record("0007-b.md", "- `core/src/a/Search.kt`")
        self.assertEqual(1, len(problems := self.problems()))
        self.assertIn("share number 0007", problems[0])
        self.assertIn("highest on the latest main plus one", problems[0])


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


if __name__ == "__main__":
    unittest.main()
