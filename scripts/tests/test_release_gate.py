from __future__ import annotations

import os
import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPTS_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import release_gate as gate  # noqa: E402


def build_text(name: str = "1.4.0", code: str = "1_500_010_400") -> str:
    return f'''
    defaultConfig {{
        versionCode = {code}
        versionName = "{name}"
        if (versionCodeOverride != null) {{
            versionCode = versionCodeOverride
        }}
    }}
    '''


class ParseReleaseTest(unittest.TestCase):
    def test_reads_the_release_identifiers(self) -> None:
        release = gate.parse_release(build_text())
        self.assertEqual("1.4.0", release.name)
        self.assertEqual("v1.4.0", release.tag)
        self.assertEqual("release-notes/v1.4.0.md", release.notes_file)

    def test_rejects_a_code_the_updater_would_misorder(self) -> None:
        with self.assertRaisesRegex(gate.ReleaseError, "1500010400"):
            gate.parse_release(build_text(code="140"))

    def test_rejects_a_component_that_would_collide_in_the_code(self) -> None:
        with self.assertRaisesRegex(gate.ReleaseError, "below 100"):
            gate.parse_release(build_text(name="1.100.0", code="1_500_020_000"))


class PublishedVersionTest(unittest.TestCase):
    def test_compares_numerically_and_ignores_other_tags(self) -> None:
        release = gate.Release((1, 10, 0))
        gate.require_newer_than_published(release, ["v1.9.9", "nightly", "v1.2.0"])

    def test_rejects_a_republished_or_older_version(self) -> None:
        for version in [(1, 4, 0), (1, 3, 9)]:
            with self.assertRaises(gate.ReleaseError):
                gate.require_newer_than_published(gate.Release(version), ["v1.4.0"])


class ReleaseOnlyCommitTest(unittest.TestCase):
    version_bump = (
        "--- a/app/build.gradle.kts\n+++ b/app/build.gradle.kts\n@@ -150,2 +150,2 @@\n"
        "-        versionCode = 1_500_010_400\n+        versionCode = 1_500_010_401\n"
        '-        versionName = "1.4.0"\n+        versionName = "1.4.1"\n'
    )

    def test_version_notes_changelog_and_profiles_keep_the_earlier_result(self) -> None:
        paths = [
            gate.APP_BUILD,
            "CHANGELOG.md",
            "release-notes/v1.4.1.md",
            "app/src/release/generated/baselineProfiles/baseline-prof.txt",
        ]
        self.assertTrue(gate.is_release_only(paths, self.version_bump))

    def test_any_other_build_file_change_needs_a_new_check(self) -> None:
        diff = self.version_bump + "+        minSdk = 30\n"
        self.assertFalse(gate.is_release_only([gate.APP_BUILD], diff))

    def test_a_source_change_needs_a_new_check(self) -> None:
        paths = ["CHANGELOG.md", "app/src/main/java/Feature.kt"]
        self.assertFalse(gate.is_release_only(paths, ""))


def listing(*paths: str, blob: str = "a" * 40) -> str:
    return "".join(f"100644 blob {blob}\t{path}\0" for path in paths)


class InputKeyTest(unittest.TestCase):
    code = ("app/src/main/java/Feature.kt", "gradle.properties", ".github/workflows/verify.yml")

    def test_prose_notes_and_other_workflows_keep_the_key(self) -> None:
        extra = ("README.md", ".docs/notes.md", "release-notes/v1.0.0.md", ".github/workflows/release.yml")
        self.assertEqual(gate.input_key(listing(*self.code)), gate.input_key(listing(*self.code, *extra)))

    def test_build_inputs_change_the_key(self) -> None:
        base = gate.input_key(listing(*self.code))
        for path in [
            "core-sources/src/test/resources/site/README.md",
            "scripts/check.sh",
            "deploy/libretranslate/server.py",
            ".jscpd.json",
        ]:
            with self.subTest(path=path):
                self.assertNotEqual(base, gate.input_key(listing(*self.code, path)))

    def test_content_changes_the_key(self) -> None:
        self.assertNotEqual(gate.input_key(listing(*self.code)), gate.input_key(listing(*self.code, blob="b" * 40)))


class ResultRefTest(unittest.TestCase):
    """record and has against a throwaway origin, including the release-only exception."""

    def setUp(self) -> None:
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.repo = pathlib.Path(temp.name, "repo")
        origin = pathlib.Path(temp.name, "origin.git")
        subprocess.run(["git", "init", "-q", "--bare", origin], check=True)
        subprocess.run(["git", "init", "-q", self.repo], check=True)
        self.git("remote", "add", "origin", str(origin))
        self.commit({gate.APP_BUILD: 'versionCode = 1\nversionName = "1.0.0"\n'})

    def git(self, *arguments: str) -> None:
        env = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
               "GIT_COMMITTER_EMAIL": "t@t"}
        subprocess.run(["git", *arguments], cwd=self.repo, env=env, check=True, capture_output=True)

    def commit(self, files: dict[str, str]) -> None:
        for path, text in files.items():
            (self.repo / path).parent.mkdir(parents=True, exist_ok=True)
            (self.repo / path).write_text(text)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "change")

    def gate(self, *arguments: str) -> int:
        return subprocess.run(
            [sys.executable, SCRIPTS_DIR / "release_gate.py", *arguments], cwd=self.repo, capture_output=True
        ).returncode

    def test_each_kind_is_recorded_separately(self) -> None:
        self.assertEqual(1, self.gate("has", "check"))
        self.assertEqual(0, self.gate("record", "check"))
        self.assertEqual(0, self.gate("has", "check"))
        self.assertEqual(1, self.gate("has", "release"))

    def test_a_docs_commit_reuses_the_result_and_a_source_commit_does_not(self) -> None:
        self.gate("record", "check")
        self.commit({"README.md": "docs"})
        self.assertEqual(0, self.gate("has", "check"))
        self.commit({"app/src/main/Feature.kt": "code"})
        self.assertEqual(1, self.gate("has", "check"))

    def test_a_version_bump_reuses_the_parent_result(self) -> None:
        self.gate("record", "release")
        self.commit({gate.APP_BUILD: 'versionCode = 2\nversionName = "1.0.1"\n', "release-notes/v1.0.1.md": "n"})
        self.assertEqual(0, self.gate("has", "release"))


if __name__ == "__main__":
    unittest.main()
