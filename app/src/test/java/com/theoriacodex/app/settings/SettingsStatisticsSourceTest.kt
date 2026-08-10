package com.theoriacodex.app.settings

import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.statistics.AppUsageTracker
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStatisticsSourceTest {
    @Test
    fun `current library projection follows the active profile and retains lifetime counters`() = runTest {
        val codexRepository = InMemoryCodexRepository()
        val statisticsRepository = InMemoryStatisticsRepository()
        val mainCodex = codexRepository.ensureCodex(
            profileScopedCodexId("profile-main", "main"),
            "Main collection",
        )
        val altCodex = codexRepository.ensureCodex(
            profileScopedCodexId("profile-alt", "alt"),
            "Alt collection",
        )
        codexRepository.addItem(mainCodex.codexId, post(SourceKey.PIXIV, "main"))
        codexRepository.addItem(altCodex.codexId, post(SourceKey.GELBOORU, "alt"))
        statisticsRepository.recordWatchedPost(SourceKey.HITOMI, setOf("watched"))
        statisticsRepository.recordCodexEntry(mainCodex.codexId)
        val tracker = AppUsageTracker(
            repository = statisticsRepository,
            scope = backgroundScope,
            elapsedRealtime = { 0L },
            tickIntervalMs = 0L,
        )
        val profiles = MutableStateFlow("profile-main")
        val source = SettingsStatisticsSource(statisticsRepository, codexRepository, tracker)

        val main = source.observe(profiles).first { summary -> summary.savedPostCount == 1L }
        profiles.value = "profile-alt"
        val alt = source.observe(profiles).first { summary ->
            summary.savedSources.singleOrNull()?.source == SourceKey.GELBOORU
        }

        assertEquals(SourceKey.PIXIV, main.savedSources.single().source)
        assertEquals("Main collection", main.mostUsedCodex?.name)
        assertEquals(1L, main.watchedPostCount)
        assertEquals(SourceKey.GELBOORU, alt.savedSources.single().source)
        assertEquals("Alt collection", alt.mostUsedCodex?.name)
        assertEquals(0L, alt.mostUsedCodex?.entryCount)
        assertEquals(1L, alt.watchedPostCount)
        tracker.close()
    }

    private fun post(source: SourceKey, id: String): Post {
        return Post(
            id = PostId(source, id),
            preview = ImageRef(url = null, localPath = null, mime = null),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = listOf("saved"),
            rawTags = listOf("saved"),
            authorName = null,
            createdAtEpochMs = null,
        )
    }
}
