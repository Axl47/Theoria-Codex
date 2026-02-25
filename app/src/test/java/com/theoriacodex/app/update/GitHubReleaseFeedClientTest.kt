package com.theoriacodex.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubReleaseFeedClientTest {
    @Test
    fun `tag parser extracts version code and sha`() {
        val parsed = MainReleaseTagParser.parse(
            channel = "main",
            tagName = "main-vc12034-a1b2c3d",
        )

        checkNotNull(parsed)
        assertEquals(12034, parsed.versionCode)
        assertEquals("a1b2c3d", parsed.commitShaShort)
    }

    @Test
    fun `tag parser rejects malformed tags`() {
        assertNull(MainReleaseTagParser.parse("main", "main-v12034-a1b2c3d"))
        assertNull(MainReleaseTagParser.parse("main", "stable-vc12034-a1b2c3d"))
        assertNull(MainReleaseTagParser.parse("main", "main-vc0-a1b2c3d"))
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
                "tag_name": "main-vc12031-bbbbbbb",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-02-25T20:00:00Z",
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
}
