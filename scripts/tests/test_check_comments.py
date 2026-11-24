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

    def test_tool_directives_are_allowed(self) -> None:
        source = "// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; "
        source += "see LICENSE-APACHE-2.0.txt in this module.\n"
        source += "// Derived from Mihon (https://github.com/mihonapp/mihon), Fake License. TODO\n"
        source += "//noinspection AndroidLintSdCardPath and prose\n"
        source += "//noinspection AndroidLintSdCardPath\n// @formatter:off\n// prose\n/* @formatter:on\nTODO */\n"
        path = write_source(self, source)
        self.assertEqual(
            [c.text for c in check_comments.banned_comments(path)],
            [
                "// Derived from Mihon (https://github.com/mihonapp/mihon), Fake License. TODO",
                "//noinspection AndroidLintSdCardPath and prose",
                "// prose",
                "/* @formatter:on\nTODO */",
            ],
        )


class BanTest(unittest.TestCase):
    def test_every_comment_fails_under_one_summary_per_file(self) -> None:
        path = write_source(self, "fun a() = 1\n// new\n/** Doc.\n second */\n").as_posix()
        summary, *locations = check_comments.check([pathlib.Path(path)])[0].splitlines()
        self.assertTrue(summary.startswith(f"{path}: 2 comments. Code comments are banned"))
        self.assertEqual(locations, [f"  {path}:2: // new", f"  {path}:3: /** Doc."])


if __name__ == "__main__":
    unittest.main()
