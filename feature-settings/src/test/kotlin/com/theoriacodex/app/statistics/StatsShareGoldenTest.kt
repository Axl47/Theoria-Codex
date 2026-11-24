package com.theoriacodex.app.statistics

import com.theoriacodex.data.storage.assertStoredFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsShareGoldenTest {
    @Test
    fun `files older builds exported import exactly like current exports`() {
        assertEquals(shared, parseStatsShare(golden("gson-export.json")))
        assertEquals(shared.copy(name = null, overall = null), parseStatsShare(golden("gson-export-no-overall.json")))
        assertEquals(shared, parseStatsShare(encodeStatsShare(shared)))
    }

    @Test
    fun `the exports hold every stored key`() {
        val series = StatsShareFile(
            format = "theoria-codex-stats",
            version = 1,
            codices = emptyList(),
            overall = StatsShareOverall(timeInAppMs = 30L, readingMs = 10L, animeMs = 20L),
        )
        assertStoredFormat(golden("gson-export.json") to shared, golden("export-series.json") to series)
    }

    private val shared = StatsShareFile(
        format = "theoria-codex-stats",
        version = 1,
        name = "Sam & <Al>'s",
        codices = listOf(
            StatsShareCodex(
                "Land & <scapes>", 3L, 4L, 5L, 6L,
                listOf(StatsSharePost("GELBOORU:1", "Post = 1", 7L, 8L, 9L), StatsSharePost("PIXIV:2", "It's", 1L, 0L, 2L)),
            ),
            StatsShareCodex("Empty", 1L, 0L, 0L, 0L, emptyList()),
        ),
        overall = StatsShareOverall(
            timeInAppMs = 10L, browsingMs = 11L, watchingMs = 12L, codexMs = 13L,
            appOpens = 14L, postsWatched = 15L, postsSaved = 16L, searches = 17L,
        ),
    )

    private fun golden(name: String) =
        checkNotNull(javaClass.getResource("/golden/stats-share/$name")) { "missing golden $name" }.readText()
}
