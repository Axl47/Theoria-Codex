from __future__ import annotations

import pathlib
import sys
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


if __name__ == "__main__":
    unittest.main()
