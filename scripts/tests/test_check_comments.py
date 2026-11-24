from __future__ import annotations

import pathlib
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

    def test_finds_comments_inside_string_templates(self) -> None:
        self.assertEqual(texts('val a = "${ b /* inner */ }"'), ["/* inner */"])

    def test_kotlin_block_comments_nest_but_java_ones_do_not(self) -> None:
        source = "/* outer /* inner */ still */ x"
        self.assertEqual(texts(source), [source[:-2]])
        self.assertEqual(texts(source, kotlin=False), ["/* outer /* inner */"])

    def test_tool_directives_are_not_counted(self) -> None:
        source = "//noinspection AndroidLintSdCardPath\n// @formatter:off\n// prose\n"
        path = write_source(self, source)
        self.assertEqual([c.text for c in check_comments.counted_comments(path)], ["// prose"])


class RatchetTest(unittest.TestCase):
    def test_baseline_only_shrinks(self) -> None:
        self.assertEqual(check_comments.check({"a.kt": 2}, {"a.kt": 2}), [])
        self.assertIn("--update", check_comments.check({"a.kt": 2}, {"a.kt": 1})[0])
        self.assertIn("--update", check_comments.check({"a.kt": 2}, {})[0])

    def test_new_comments_fail(self) -> None:
        path = str(write_source(self, "// new\n"))
        problem = check_comments.check({}, {path: 1})[0]
        self.assertIn("banned", problem)
        self.assertIn(f"{path}:1: // new", problem)


if __name__ == "__main__":
    unittest.main()
