package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatisticsRepositoriesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `in memory repository records each lifetime dimension without cross counting`() = runTest {
        val repository = InMemoryStatisticsRepository()

        repository.recordAppOpen()
        repository.addUsageDuration(
            UsageDurationDelta(totalMs = 4_000L, browsingMs = 1_500L, watchingMs = 2_000L)
        )
        repository.recordWatchedPost(SourceKey.PIXIV, setOf(" blue_hair ", "blue_hair", ""))
        repository.recordSearch(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), setOf(" animation ", "animation", ""))
        repository.recordForYouSearch()
        repository.recordForYouSave()
        repository.recordPostUrlCopy()
        repository.recordCodexEntry(" codex-1 ")
        repository.recordTranslationUsage(phraseCount = 2L, sourceCharacterCount = 7L)

        val statistics = repository.observeStatistics().first()
        assertEquals(1L, statistics.appOpenCount)
        assertEquals(4_000L, statistics.totalForegroundMs)
        assertEquals(1_500L, statistics.browsingMs)
        assertEquals(2_000L, statistics.watchingMs)
        assertEquals(0L, statistics.codexMs)
        assertEquals(1L, statistics.watchedPostCount)
        assertEquals(1L, statistics.watchedBySource.getValue(SourceKey.PIXIV))
        assertEquals(1L, statistics.watchedByTag.getValue(StatisticsTagKey(SourceKey.PIXIV, "blue_hair")))
        assertEquals(1L, statistics.searchCount)
        assertEquals(1L, statistics.searchesBySource.getValue(SourceKey.PIXIV))
        assertEquals(1L, statistics.searchesBySource.getValue(SourceKey.GELBOORU))
        assertEquals(
            mapOf(StatisticsTagKey(SourceKey.PIXIV, "animation") to 1L, StatisticsTagKey(SourceKey.GELBOORU, "animation") to 1L),
            statistics.searchesByTag,
        )
        assertEquals(1L, statistics.forYouSearchCount)
        assertEquals(1L, statistics.forYouSaveCount)
        assertEquals(1L, statistics.postUrlCopyCount)
        assertEquals(mapOf("codex-1" to 1L), statistics.codexEntryCounts)
        assertEquals(2L, statistics.translatedPhraseCount)
        assertEquals(7L, statistics.translatedSourceCharacterCount)
    }

    @Test
    fun `searched tags seed once from manual recent searches without double counting`() = runTest {
        val repository = InMemoryStatisticsRepository()
        repository.recordSearch(setOf(SourceKey.PIXIV), setOf("animation"))
        fun entry(tags: List<String>, kind: RecentSearchKind, sources: List<SourceKey>) = RecentSearchEntry(
            query = Query(QueryMode.Unified, includeTags = tags, excludeTags = emptyList(), sort = SortMode.NEWEST,
                dateRange = null, minScore = null),
            queryHash = tags.joinToString(),
            searchedAtEpochMs = 0L,
            kind = kind,
            sources = sources,
        )
        val recents = listOf(
            entry(listOf("animation", "sky"), RecentSearchKind.UNIFIED, listOf(SourceKey.PIXIV, SourceKey.GELBOORU)),
            entry(listOf("sky"), RecentSearchKind.SOURCE, listOf(SourceKey.GELBOORU)),
            entry(listOf("seed"), RecentSearchKind.FYP, listOf(SourceKey.PIXIV)),
        )

        repository.seedSearchedTags(recents)
        repository.seedSearchedTags(recents)

        val statistics = repository.observeStatistics().first()
        assertEquals(
            mapOf(
                StatisticsTagKey(SourceKey.PIXIV, "animation") to 1L,
                StatisticsTagKey(SourceKey.GELBOORU, "animation") to 1L,
                StatisticsTagKey(SourceKey.PIXIV, "sky") to 1L,
                StatisticsTagKey(SourceKey.GELBOORU, "sky") to 2L,
            ),
            statistics.searchesByTag,
        )
        assertTrue(statistics.searchesByTagSeeded)
    }

    @Test
    fun `statistics round trip through typed DataStore and retain source aware tags`() = runTest {
        val directory = tempFolder.newFolder("statistics-round-trip")
        val firstScope = newScope()
        val first = DataStoreStatisticsRepository(directory, firstScope)
        first.recordWatchedPost(SourceKey.PIXIV, setOf("landscape"), CodexPostOpen("first", "PIXIV:1", "A post"))
        first.recordWatchedPost(SourceKey.GELBOORU, setOf("landscape"))
        first.recordSearch(setOf(SourceKey.PIXIV), setOf("animation"))
        first.addUsageDuration(UsageDurationDelta(totalMs = 12_345L, codexMs = 2_345L))
        first.recordTranslationUsage(phraseCount = 3L, sourceCharacterCount = 18L)
        first.recordCodexPostOpen("first", "PIXIV:1", "A post")
        first.addCodexUsage(CodexUsageTarget("first"), 100L)
        first.addCodexUsage(CodexUsageTarget("first", "PIXIV:1", video = true), 250L)
        first.addCodexUsage(CodexUsageTarget("first", "PIXIV:1"), 50L)
        first.addCodexUsage(CodexUsageTarget("second", "PIXIV:1"), 80L)
        closeScope(firstScope)

        val secondScope = newScope()
        val second = DataStoreStatisticsRepository(directory, secondScope)
        val restored = second.observeStatistics().first()

        val codex = restored.codexStatistics.getValue("first")
        assertEquals(100L, codex.browsingMs)
        assertEquals(300L, codex.watchingMs)
        assertEquals(CodexPostStatistics("A post", 2L, 250L, 50L), codex.posts.getValue("PIXIV:1"))
        assertEquals(80L, restored.codexStatistics.getValue("second").watchingMs)
        assertEquals(2L, restored.watchedPostCount)
        assertEquals(1L, restored.watchedByTag.getValue(StatisticsTagKey(SourceKey.PIXIV, "landscape")))
        assertEquals(1L, restored.watchedByTag.getValue(StatisticsTagKey(SourceKey.GELBOORU, "landscape")))
        assertEquals(mapOf(StatisticsTagKey(SourceKey.PIXIV, "animation") to 1L), restored.searchesByTag)
        assertEquals(12_345L, restored.totalForegroundMs)
        assertEquals(2_345L, restored.codexMs)
        assertEquals(3L, restored.translatedPhraseCount)
        assertEquals(18L, restored.translatedSourceCharacterCount)
        assertTrue(directory.resolve(DATASTORE_STATISTICS_FILE_NAME).isFile)
        closeScope(secondScope)
    }

    @Test
    fun `concurrent statistics mutations serialize without lost counts`() = runTest {
        val directory = tempFolder.newFolder("statistics-concurrency")
        val scope = newScope()
        val repository = DataStoreStatisticsRepository(directory, scope)

        coroutineScope {
            (0 until 40).map {
                async(Dispatchers.Default) {
                    repository.recordPostUrlCopy()
                }
            }.awaitAll()
        }

        assertEquals(40L, repository.observeStatistics().first().postUrlCopyCount)
        closeScope(scope)
    }

    @Test
    fun `statistics additions saturate and negative deltas are ignored`() = runTest {
        val repository = InMemoryStatisticsRepository(
            LifetimeStatistics(
                appOpenCount = Long.MAX_VALUE,
                totalForegroundMs = Long.MAX_VALUE - 1L,
                translatedPhraseCount = Long.MAX_VALUE,
                translatedSourceCharacterCount = Long.MAX_VALUE - 1L,
            )
        )

        repository.recordAppOpen()
        repository.addUsageDuration(UsageDurationDelta(totalMs = 50L, browsingMs = -50L))
        repository.recordTranslationUsage(phraseCount = 1L, sourceCharacterCount = 2L)

        val statistics = repository.observeStatistics().first()
        assertEquals(Long.MAX_VALUE, statistics.appOpenCount)
        assertEquals(Long.MAX_VALUE, statistics.totalForegroundMs)
        assertEquals(0L, statistics.browsingMs)
        assertEquals(Long.MAX_VALUE, statistics.translatedPhraseCount)
        assertEquals(Long.MAX_VALUE, statistics.translatedSourceCharacterCount)
    }

    @Test
    fun `existing statistics files default translation usage to zero`() = runTest {
        val directory = tempFolder.newFolder("statistics-before-translation-usage")
        directory.resolve(DATASTORE_STATISTICS_FILE_NAME).writeText(
            """{"schemaVersion":1,"statistics":{"appOpenCount":4}}"""
        )
        val scope = newScope()
        val statistics = DataStoreStatisticsRepository(directory, scope).observeStatistics().first()

        assertEquals(4L, statistics.appOpenCount)
        assertEquals(0L, statistics.translatedPhraseCount)
        assertEquals(0L, statistics.translatedSourceCharacterCount)
        closeScope(scope)
    }

    @Test
    fun `newer statistics schema fails closed without replacing the file`() = runTest {
        val directory = tempFolder.newFolder("statistics-future-schema")
        val destination = directory.resolve(DATASTORE_STATISTICS_FILE_NAME)
        val future = """{"schemaVersion":99,"statistics":{}}"""
        destination.writeText(future)
        val scope = newScope()
        val repository = DataStoreStatisticsRepository(directory, scope)

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { repository.awaitReady() }
        }

        assertEquals(future, destination.readText())
        assertFalse(directory.resolve("$DATASTORE_STATISTICS_FILE_NAME.corrupt").exists())
        closeScope(scope)
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun closeScope(scope: CoroutineScope) {
        scope.cancel()
        scope.coroutineContext.job.join()
    }
}
