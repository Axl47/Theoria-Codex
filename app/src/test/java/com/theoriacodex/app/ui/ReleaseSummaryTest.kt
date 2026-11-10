package com.theoriacodex.app.ui

import com.theoriacodex.app.update.ReleaseChangelogEntry
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseSummaryTest {
    @Test
    fun `summary shows version size and publish date`() {
        val release = entry(sizeBytes = 10_347_639, publishedAt = Instant.parse("2026-11-10T01:10:25Z"))

        assertEquals("v1.4.0 • 10347639 bytes • Nov 10", summary(release))
    }

    @Test
    fun `summary omits details GitHub did not supply`() {
        assertEquals("v1.4.0", summary(entry(sizeBytes = null, publishedAt = null)))
    }

    private fun summary(release: ReleaseChangelogEntry) =
        releaseSummary(release, formatSize = { "$it bytes" }, zone = ZoneOffset.UTC, locale = Locale.US)

    private fun entry(sizeBytes: Long?, publishedAt: Instant?) = ReleaseChangelogEntry(
        releaseId = 1,
        versionCode = 1_500_010_400,
        commitShaShort = "unknown",
        releaseName = "v1.4.0",
        publishedAtEpochMs = publishedAt?.toEpochMilli(),
        changelogMarkdown = "",
        changelogSections = emptyList(),
        assetSizeBytes = sizeBytes,
    )
}
