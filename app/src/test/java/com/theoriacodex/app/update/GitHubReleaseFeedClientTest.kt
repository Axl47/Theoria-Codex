package com.theoriacodex.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseFeedClientTest {
    @Test
    fun `tag parser extracts version code and sha from legacy main tag`() {
        val parsed = MainReleaseTagParser.parse(
            channel = "main",
            tagName = "main-vc12034-a1b2c3d",
        )

        checkNotNull(parsed)
        assertEquals(12034, parsed.versionCode)
        assertEquals("a1b2c3d", parsed.commitShaShort)
    }

    @Test
    fun `tag parser extracts version code from semver tag`() {
        val parsed = MainReleaseTagParser.parse(
            channel = "main",
            tagName = "v0.1.11",
            fallbackCommitSha = "1234567890abcdef1234567890abcdef12345678",
        )

        checkNotNull(parsed)
        assertEquals(1_500_000_111, parsed.versionCode)
        assertEquals("1234567", parsed.commitShaShort)
    }

    @Test
    fun `tag parser rejects malformed tags`() {
        assertNull(MainReleaseTagParser.parse("main", "main-v12034-a1b2c3d"))
        assertNull(MainReleaseTagParser.parse("main", "stable-vc12034-a1b2c3d"))
        assertNull(MainReleaseTagParser.parse("main", "main-vc0-a1b2c3d"))
        assertNull(MainReleaseTagParser.parse("main", "v1.2"))
        assertNull(MainReleaseTagParser.parse("main", "v1.100.2"))
    }

    @Test
    fun `release parser picks newest prerelease with fixed asset`() {
        val json =
            """
            [
              {
                "id": 1,
                "tag_name": "main-vc12030-aaaaaaa",
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
                "tag_name": "main-vc12031-bbbbbbb",
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
                "tag_name": "main-vc12032-ccccccc",
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

        val remote = GitHubReleaseFeedClient.parseLatestMainPrerelease(
            jsonBody = json,
            channel = "main",
            assetName = "theoria-codex-main.apk",
        )

        checkNotNull(remote)
        assertEquals(2L, remote.releaseId)
        assertEquals(12031, remote.versionCode)
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
        val json =
            """
            [
              {
                "id": 10,
                "name": "Main Build vc100...8",
                "tag_name": "main-vc1008-aaaaaaa",
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
                "tag_name": "main-vc1009-bbbbbbb",
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
                "tag_name": "main-vc1010-ccccccc",
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

        val history = GitHubReleaseFeedClient.parseMainPrereleaseHistory(
            jsonBody = json,
            channel = "main",
            assetName = "theoria-codex-main.apk",
        )

        assertEquals(2, history.size)
        assertEquals(11L, history[0].releaseId)
        assertEquals(1009, history[0].versionCode)
        assertEquals(10L, history[1].releaseId)
        assertEquals(1008, history[1].versionCode)
    }

    @Test
    fun `release parser returns null when fixed asset missing`() {
        val json =
            """
            [
              {
                "id": 1,
                "tag_name": "main-vc12030-aaaaaaa",
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

        val remote = GitHubReleaseFeedClient.parseLatestMainPrerelease(
            jsonBody = json,
            channel = "main",
            assetName = "theoria-codex-main.apk",
        )

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

        val remote = GitHubReleaseFeedClient.parseLatestMainPrerelease(
            jsonBody = json,
            channel = "main",
            assetName = "theoria-codex-main.apk",
        )

        checkNotNull(remote)
        assertEquals(88L, remote.releaseId)
        assertEquals(1_500_000_111, remote.versionCode)
        assertEquals("89abcde", remote.commitShaShort)
        assertEquals("https://example.com/main.apk", remote.assetDownloadUrl)
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
}
