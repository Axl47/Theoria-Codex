from __future__ import annotations

import os
import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPTS_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import check_comments  # noqa: E402


def write_source(test: unittest.TestCase, source: str) -> pathlib.Path:
    directory = tempfile.TemporaryDirectory()
    test.addCleanup(directory.cleanup)
    path = pathlib.Path(directory.name) / "Sample.kt"
    path.write_text(source, encoding="utf-8")
    return path


def texts(source: str, kotlin: bool = True) -> list[str]:
    return [comment.text for comment in check_comments.find_comments(source, kotlin)]


class FindCommentsTest(unittest.TestCase):
    def test_finds_line_block_and_kdoc_comments(self) -> None:
        source = '/** Doc. */\nfun a() = 1 // trailing\n/* block */\n'
        self.assertEqual(texts(source), ["/** Doc. */", "// trailing", "/* block */"])

    def test_skips_comment_markers_inside_literals(self) -> None:
        source = (
            'val url = "https://example.com/*"\n'
            'val raw = """ // not a comment """\n'
            "val quote = '\"'\n"
            'val template = "${values.joinToString("//")} /*"\n'
        )
        self.assertEqual(texts(source), [])

    def test_backticked_names_are_not_character_literals(self) -> None:
        source = "fun `doesn't crash`() = Unit\n// after\n"
        self.assertEqual(texts(source), ["// after"])

    def test_java_text_blocks_honour_escaped_quotes(self) -> None:
        source = 'String a = """\n  \\""" // inside\n  """; // after\n'
        self.assertEqual(texts(source, kotlin=False), ["// after"])

    def test_finds_comments_inside_string_templates(self) -> None:
        self.assertEqual(texts('val a = "${ b /* inner */ }"'), ["/* inner */"])

    def test_kotlin_block_comments_nest_but_java_ones_do_not(self) -> None:
        source = "/* outer /* inner */ still */ x"
        self.assertEqual(texts(source), [source[:-2]])
        self.assertEqual(texts(source, kotlin=False), ["/* outer /* inner */"])

    def test_tool_directives_are_not_counted(self) -> None:
        source = "// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; "
        source += "see LICENSE-APACHE-2.0.txt in this module.\n"
        source += "// Derived from Mihon (https://github.com/mihonapp/mihon), Fake License. TODO\n"
        source += "//noinspection AndroidLintSdCardPath and prose\n"
        source += "//noinspection AndroidLintSdCardPath\n// @formatter:off\n// prose\n/* @formatter:on\nTODO */\n"
        path = write_source(self, source)
        self.assertEqual(
            [c.text for c in check_comments.counted_comments(path)],
            [
                "// Derived from Mihon (https://github.com/mihonapp/mihon), Fake License. TODO",
                "//noinspection AndroidLintSdCardPath and prose",
                "// prose",
                "/* @formatter:on\nTODO */",
            ],
        )


class RatchetTest(unittest.TestCase):
    def test_baseline_only_shrinks(self) -> None:
        self.assertEqual(check_comments.check({"a.kt": 2}, {"a.kt": 2}), [])
        self.assertIn("--update", check_comments.check({"a.kt": 2}, {"a.kt": 1})[0])
        self.assertIn("--update", check_comments.check({"a.kt": 2}, {})[0])

    def test_baseline_may_not_rise_above_the_base(self) -> None:
        problems = check_comments.check({"a.kt": 3, "b.kt": 1}, {"a.kt": 3, "b.kt": 1}, base={"a.kt": 2})
        self.assertEqual(len(problems), 2)
        self.assertTrue(all("may only shrink" in problem for problem in problems))

    def test_unknown_base_fails(self) -> None:
        with self.assertRaises(subprocess.CalledProcessError):
            check_comments.baseline_at("no-such-revision")

    def test_new_comments_fail(self) -> None:
        path = str(write_source(self, "// new\n"))
        problem = check_comments.check({}, {path: 1})[0]
        self.assertIn("banned", problem)
        self.assertIn(f"{path}:1: // new", problem)


class RenameTest(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.addCleanup(os.chdir, os.getcwd())
        os.chdir(directory.name)
        self.git("init", "-q")
        pathlib.Path("Old.kt").write_text("// kept\nfun a() = 1\nfun b() = 2\nfun c() = 3\n", encoding="utf-8")
        check_comments.BASELINE.parent.mkdir(parents=True)
        check_comments.write_baseline({"Old.kt": 1})
        self.git("add", ".")
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "base")
        self.git("mv", "Old.kt", "New.kt")

    def git(self, *args: str) -> None:
        subprocess.run(["git", *args], check=True)

    def test_finds_renames_against_the_base(self) -> None:
        self.assertEqual(check_comments.renames("HEAD"), {"Old.kt": "New.kt"})

    def test_moved_file_keeps_its_allowance_and_may_not_raise_it(self) -> None:
        base = check_comments.carried({"Old.kt": 1}, check_comments.renames("HEAD"))
        self.assertEqual(check_comments.check({"New.kt": 1}, {"New.kt": 1}, base), [])
        self.assertIn("may only shrink", check_comments.check({"New.kt": 2}, {"New.kt": 2}, base)[0])
        self.assertIn("--update", check_comments.check({"Old.kt": 1, "New.kt": 1}, {"New.kt": 1}, base)[0])

    def test_unmoved_entry_points_to_update_instead_of_deleting(self) -> None:
        base = check_comments.carried({"Old.kt": 1}, check_comments.renames("HEAD"))
        problems = check_comments.check({"Old.kt": 1}, {"New.kt": 1}, base)
        self.assertEqual(len(problems), 2)
        self.assertTrue(all("--update" in problem and "banned" not in problem for problem in problems))

    def test_update_carries_an_allowance_across_two_renames(self) -> None:
        check_comments.write_baseline({"New.kt": 1})
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "first move")
        self.git("mv", "New.kt", "Final.kt")
        script = str(SCRIPTS_DIR / "check_comments.py")
        subprocess.run([sys.executable, script, "--update", "--base", "HEAD^"], check=True)
        self.assertEqual(check_comments.read_baseline(), {"Final.kt": 1})


if __name__ == "__main__":
    unittest.main()
