package com.theoriacodex.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseFeedClientTest {
    @Test
    fun `tag parser extracts version code from semver tag`() {
        val parsed = MainReleaseTagParser.parse(
            tagName = "v0.1.11",
            fallbackCommitSha = "1234567890abcdef1234567890abcdef12345678",
        )

        checkNotNull(parsed)
        assertEquals(1_500_000_111, parsed.versionCode)
        assertEquals("1234567", parsed.commitShaShort)
    }

    @Test
    fun `tag parser rejects malformed tags`() {
        assertNull(MainReleaseTagParser.parse("1.2.3"))
        assertNull(MainReleaseTagParser.parse("v1.2"))
        assertNull(MainReleaseTagParser.parse("v1.100.2"))
    }

    @Test
    fun `release parser picks newest prerelease with fixed asset`() {
        val json = mixedReleaseJson()

        val remote = GitHubReleaseFeedClient.parseMainPrereleaseHistory(
            jsonBody = json,
            assetName = "theoria-codex-main.apk",
        ).firstOrNull()

        checkNotNull(remote)
        assertEquals(2L, remote.releaseId)
        assertEquals(1_500_010_031, remote.versionCode)
        assertEquals("bbbbbbb", remote.commitShaShort)
        assertEquals("https://example.com/b.apk", remote.assetDownloadUrl)
        assertEquals(456L, remote.assetSizeBytes)
        assertEquals("Main build vc12031 (bbbbbbb)", remote.releaseName)
        assertNotNull(remote.publishedAtEpochMs)
        assertEquals(2, remote.changelogSections.size)
        assertEquals("Highlights", remote.changelogSections[0].title)
        assertEquals("Improved startup updater prompt", remote.changelogSections[0].bullets.first())
        assertEquals("Fixes", remote.changelogSections[1].title)
        assertEquals("Fixed 404 release URL edge case", remote.changelogSections[1].bullets.first())
    }

    @Test
    fun `release history parser returns sorted prerelease history`() {
        val json = releaseHistoryJson()

        val history = GitHubReleaseFeedClient.parseMainPrereleaseHistory(
            jsonBody = json,
            assetName = "theoria-codex-main.apk",
        )

        assertEquals(2, history.size)
        assertEquals(11L, history[0].releaseId)
        assertEquals(1_500_010_009, history[0].versionCode)
        assertEquals(10L, history[1].releaseId)
        assertEquals(1_500_010_008, history[1].versionCode)
    }

    @Test
    fun `release parser returns null when fixed asset missing`() {
        val json =
            """
            [
              {
                "id": 1,
                "tag_name": "v1.0.30",
                "target_commitish": "aaaaaaa",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-02-24T20:00:00Z",
                "assets": [
                  {
                    "name": "other.apk",
                    "browser_download_url": "https://example.com/other.apk"
                  }
                ]
              }
            ]
            """.trimIndent()

        val remote = GitHubReleaseFeedClient.parseMainPrereleaseHistory(
            jsonBody = json,
            assetName = "theoria-codex-main.apk",
        ).firstOrNull()

        assertNull(remote)
    }

    @Test
    fun `release parser accepts semver tag naming`() {
        val json =
            """
            [
              {
                "id": 88,
                "tag_name": "v0.1.11",
                "target_commitish": "89abcdef0123456789abcdef0123456789abcdef",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-02-25T20:00:00Z",
                "assets": [
                  {
                    "name": "theoria-codex-main.apk",
                    "browser_download_url": "https://example.com/main.apk",
                    "size": 777
                  }
                ]
              }
            ]
            """.trimIndent()

        val remote = GitHubReleaseFeedClient.parseMainPrereleaseHistory(
            jsonBody = json,
            assetName = "theoria-codex-main.apk",
        ).firstOrNull()

        checkNotNull(remote)
        assertEquals(88L, remote.releaseId)
        assertEquals(1_500_000_111, remote.versionCode)
        assertEquals("89abcde", remote.commitShaShort)
        assertEquals("https://example.com/main.apk", remote.assetDownloadUrl)
    }

    @Test
    fun `release parser skips a malformed release or asset and keeps the good release`() {
        val json = """
            [
              "not a release",
              {"id": {"bad": true}, "tag_name": "v1.0.10", "target_commitish": "ccccccc", "draft": false, "prerelease": true},
              {
                "id": 11,
                "tag_name": "v1.0.9",
                "target_commitish": "bbbbbbb",
                "draft": false,
                "prerelease": true,
                "assets": [
                  7,
                  {"name": "theoria-codex-main.apk", "size": "not a size"},
                  {"name": "theoria-codex-main.apk", "browser_download_url": "https://example.com/11.apk", "size": 101}
                ]
              }
            ]
        """.trimIndent()

        val history = GitHubReleaseFeedClient.parseMainPrereleaseHistory(json, "theoria-codex-main.apk")

        assertEquals(listOf(11L), history.map { it.releaseId })
        assertEquals("https://example.com/11.apk", history.single().assetDownloadUrl)
    }

    @Test
    fun `changelog parser falls back to generic section when headings missing`() {
        val sections = ReleaseChangelogParser.parse(
            """
            Startup update prompt now asks for confirmation.
            Added remind-later behavior.
            """.trimIndent()
        )

        assertEquals(1, sections.size)
        assertEquals("Changelog", sections.first().title)
        assertTrue(sections.first().bullets.isNotEmpty())
    }

    @Test
    fun `changelog parser skips empty sections with none placeholder`() {
        val sections = ReleaseChangelogParser.parse(
            """
            ## Improvements
            - None in this build.

            ## Fixes
            - Fixed crash when retrying update
            """.trimIndent()
        )

        assertEquals(1, sections.size)
        assertEquals("Fixes", sections.first().title)
        assertEquals("Fixed crash when retrying update", sections.first().bullets.first())
    }

    private fun mixedReleaseJson(): String =
        """
        [
          {
            "id": 1,
            "tag_name": "v1.0.30",
            "target_commitish": "aaaaaaa",
            "draft": false,
            "prerelease": true,
            "published_at": "2026-02-24T20:00:00Z",
            "assets": [
              {
                "name": "theoria-codex-main.apk",
                "browser_download_url": "https://example.com/a.apk",
                "size": 123
              }
            ]
          },
          {
            "id": 2,
            "name": "Main build vc12031 (bbbbbbb)",
            "tag_name": "v1.0.31",
            "target_commitish": "bbbbbbb",
            "draft": false,
            "prerelease": true,
            "published_at": "2026-02-25T20:00:00Z",
            "body": "## Highlights\n- Improved startup updater prompt\n\n## Fixes\n- Fixed 404 release URL edge case",
            "assets": [
              {
                "name": "theoria-codex-main.apk",
                "browser_download_url": "https://example.com/b.apk",
                "size": 456
              }
            ]
          },
          {
            "id": 3,
            "tag_name": "v1.0.32",
            "target_commitish": "ccccccc",
            "draft": false,
            "prerelease": false,
            "published_at": "2026-02-26T20:00:00Z",
            "assets": [
              {
                "name": "theoria-codex-main.apk",
                "browser_download_url": "https://example.com/c.apk",
                "size": 789
              }
            ]
          }
        ]
        """.trimIndent()


    private fun releaseHistoryJson(): String =
        """
        [
          {
            "id": 10,
            "name": "Main Build vc100...8",
            "tag_name": "v1.0.8",
            "target_commitish": "aaaaaaa",
            "draft": false,
            "prerelease": true,
            "published_at": "2026-02-20T20:00:00Z",
            "body": "## Fixes\n- Older fix",
            "assets": [
              {
                "name": "theoria-codex-main.apk",
                "browser_download_url": "https://example.com/10.apk",
                "size": 100
              }
            ]
          },
          {
            "id": 11,
            "name": "Main Build vc100...9",
            "tag_name": "v1.0.9",
            "target_commitish": "bbbbbbb",
            "draft": false,
            "prerelease": true,
            "published_at": "2026-02-21T20:00:00Z",
            "body": "## New\n- Added X",
            "assets": [
              {
                "name": "theoria-codex-main.apk",
                "browser_download_url": "https://example.com/11.apk",
                "size": 101
              }
            ]
          },
          {
            "id": 12,
            "name": "Main Build vc101...0",
            "tag_name": "v1.0.10",
            "target_commitish": "ccccccc",
            "draft": true,
            "prerelease": true,
            "published_at": "2026-02-22T20:00:00Z",
            "assets": [
              {
                "name": "theoria-codex-main.apk",
                "browser_download_url": "https://example.com/12.apk",
                "size": 102
              }
            ]
          }
        ]
        """.trimIndent()
}
