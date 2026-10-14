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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `live timer and lifetime updates reuse the saved content projection`() = runTest {
        val codices = InMemoryCodexRepository()
        val statistics = InMemoryStatisticsRepository()
        val codex = codices.ensureCodex(profileScopedCodexId("profile-main", "counted"), "Counted")
        var tagReads = 0
        val tags = object : AbstractList<String>() {
            override val size = 20
            override fun get(index: Int): String { tagReads++; return "tag$index" }
        }
        codices.addItem(codex.codexId, post(SourceKey.PIXIV, "counted").copy(canonicalTags = tags, taxonomy = emptyList()))
        var time = 0L
        val tracker = AppUsageTracker(statistics, backgroundScope, elapsedRealtime = { time }, tickIntervalMs = 0L)
        val source = SettingsStatisticsSource(statistics, codices, tracker, StandardTestDispatcher(testScheduler))
        val observed = mutableListOf<com.theoriacodex.app.statistics.StatisticsSummary>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.observe(MutableStateFlow("profile-main")).collect { observed += it }
        }
        tracker.onForeground()
        runCurrent()
        val readsAfterContent = tagReads
        repeat(3) {
            time += 1_000L
            tracker.refreshLiveUsage()
            statistics.recordSearch(setOf(SourceKey.PIXIV))
            runCurrent()
        }
        assertEquals(readsAfterContent, tagReads)
        assertEquals(3_000L, observed.last().totalForegroundMs)
        assertEquals(3L, observed.last().searchCount)
        assertEquals(1L, observed.last().savedPostCount)
        codices.addItem(codex.codexId, post(SourceKey.GELBOORU, "new"))
        runCurrent()
        assertEquals(2L, observed.last().savedPostCount)
        org.junit.Assert.assertTrue(tagReads > readsAfterContent)
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
