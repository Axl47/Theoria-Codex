package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.newDataStoreScope
import com.theoriacodex.data.storage.closeDataStores
import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
        repository.recordSearch(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), setOf(SearchTerm(" animation "), SearchTerm("animation"), SearchTerm("")))
        repository.recordForYouSave()
        repository.recordPostUrlCopy()
        repository.recordCodexEntry(" codex-1 ")

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
            mapOf(searched(SourceKey.PIXIV, "animation") to 1L, searched(SourceKey.GELBOORU, "animation") to 1L),
            statistics.searchesByTag,
        )
        assertEquals(1L, statistics.forYouSaveCount)
        assertEquals(1L, statistics.postUrlCopyCount)
        assertEquals(mapOf("codex-1" to 1L), statistics.codexEntryCounts)
    }

    @Test
    fun `searched tags seed once from manual recent searches without double counting`() = runTest {
        val repository = InMemoryStatisticsRepository()
        repository.recordSearch(setOf(SourceKey.PIXIV), setOf(SearchTerm("animation")))
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
        )

        repository.seedSearchedTags(recents)
        repository.seedSearchedTags(recents)

        val statistics = repository.observeStatistics().first()
        assertEquals(
            mapOf(
                searched(SourceKey.PIXIV, "animation") to 1L,
                searched(SourceKey.GELBOORU, "animation") to 1L,
                searched(SourceKey.PIXIV, "sky") to 1L,
                searched(SourceKey.GELBOORU, "sky") to 2L,
            ),
            statistics.searchesByTag,
        )
        assertTrue(statistics.searchesByTagSeeded)
    }

    @Test
    fun `searches count typed terms with their type and only what ran`() {
        val artist = SearchTerm(" Shindol ", SearchFacet.ARTIST, " Artist ")
        val sourceQuery = query(QueryMode.Source(SourceKey.NHENTAI), SearchTerm("sky"), artist)
        val counted = StatisticsPolicies.recordSearch(LifetimeStatistics(), setOf(SourceKey.NHENTAI), sourceQuery.searchedTerms())

        assertEquals(
            mapOf(searched(SourceKey.NHENTAI, "sky") to 1L, searched(SourceKey.NHENTAI, "Shindol", SearchFacet.ARTIST, "artist") to 1L),
            counted.searchesByTag,
        )
        assertEquals(setOf(SearchTerm("sky")), query(QueryMode.Unified, SearchTerm("sky"), artist).searchedTerms())
    }

    @Test
    fun `typed terms seed once from Recents after general tags were seeded`() {
        val artist = SearchTerm("shindol", SearchFacet.ARTIST, "artist")
        val alreadySeeded = LifetimeStatistics(searchesByTag = mapOf(searched(SourceKey.NHENTAI, "sky") to 3L), searchesByTagSeeded = true)
        val recents = listOf(
            RecentSearchEntry(query(QueryMode.Source(SourceKey.NHENTAI), SearchTerm("sky"), artist), "a", 0L,
                RecentSearchKind.SOURCE, listOf(SourceKey.NHENTAI)),
            RecentSearchEntry(query(QueryMode.Source(SourceKey.NHENTAI), artist), "b", 0L,
                RecentSearchKind.SOURCE, listOf(SourceKey.NHENTAI)),
        )

        val seeded = StatisticsPolicies.seedSearchedTags(alreadySeeded, recents)

        assertEquals(
            mapOf(searched(SourceKey.NHENTAI, "sky") to 3L, SearchedTagKey(SourceKey.NHENTAI, artist) to 2L),
            seeded.searchesByTag,
        )
        assertTrue(seeded.searchesByTypedTagSeeded)
        assertEquals(seeded, StatisticsPolicies.seedSearchedTags(seeded, recents + recents))
    }

    @Test
    fun `typed counts keep their own list so older records and builds read general counts unchanged`() {
        val female = SearchedTagKey(SourceKey.HITOMI, SearchTerm("mika", SearchFacet.TAG, "female"))
        val statistics = LifetimeStatistics(
            searchesByTag = mapOf(searched(SourceKey.HITOMI, "sky") to 2L, female to 5L),
            searchesByTypedTagSeeded = true,
        )

        val record = StatisticsStoreRecord.fromDomain(statistics)

        assertEquals(listOf(StatisticsTagCountRecord("HITOMI", "sky", 2L)), record.searchesByTag)
        assertEquals(listOf(StatisticsTypedTagCountRecord("HITOMI", "mika", "TAG", "female", 5L)), record.searchesByTypedTag)
        assertEquals(statistics, record.toDomain())
        val future = record.copy(searchesByTypedTag = record.searchesByTypedTag + StatisticsTypedTagCountRecord("HITOMI", "x", "STUDIO", null, 1L))
        assertEquals(statistics, future.toDomain())
    }

    @Test
    fun `restoring a backup keeps the larger count of each typed term and both seed flags`() {
        val artist = SearchedTagKey(SourceKey.NHENTAI, SearchTerm("shindol", SearchFacet.ARTIST, "artist"))
        val current = LifetimeStatistics(searchesByTag = mapOf(artist to 2L, searched(SourceKey.NHENTAI, "sky") to 4L))
        val backup = LifetimeStatistics(searchesByTag = mapOf(artist to 5L), searchesByTagSeeded = true, searchesByTypedTagSeeded = true)

        val merged = StatisticsPolicies.mergeRestored(current, backup)

        assertEquals(mapOf(artist to 5L, searched(SourceKey.NHENTAI, "sky") to 4L), merged.searchesByTag)
        assertTrue(merged.searchesByTagSeeded && merged.searchesByTypedTagSeeded)
        assertEquals(merged, StatisticsPolicies.mergeRestored(merged, backup))
    }

    @Test
    fun `statistics round trip through typed DataStore and retain source aware tags`() = runTest {
        val directory = tempFolder.newFolder("statistics-round-trip")
        val firstScope = newDataStoreScope()
        val first = DataStoreStatisticsRepository(directory, firstScope)
        first.recordWatchedPost(SourceKey.PIXIV, setOf("landscape"), CodexPostOpen("first", "PIXIV:1", "A post"))
        first.recordWatchedPost(SourceKey.GELBOORU, setOf("landscape"))
        first.recordSearch(setOf(SourceKey.PIXIV), setOf(SearchTerm("animation"), SearchTerm("mika", SearchFacet.ARTIST)))
        first.addUsageDuration(UsageDurationDelta(totalMs = 12_345L, codexMs = 2_345L))
        first.recordCodexPostOpen("first", "PIXIV:1", "A post")
        first.addCodexUsage(CodexUsageTarget("first"), 100L)
        first.addCodexUsage(CodexUsageTarget("first", "PIXIV:1", video = true), 250L)
        first.addCodexUsage(CodexUsageTarget("first", "PIXIV:1"), 50L)
        first.addCodexUsage(CodexUsageTarget("second", "PIXIV:1"), 80L)
        firstScope.closeDataStores()

        val secondScope = newDataStoreScope()
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
        assertEquals(
            mapOf(searched(SourceKey.PIXIV, "animation") to 1L, searched(SourceKey.PIXIV, "mika", SearchFacet.ARTIST) to 1L),
            restored.searchesByTag,
        )
        assertEquals(12_345L, restored.totalForegroundMs)
        assertEquals(2_345L, restored.codexMs)
        assertTrue(directory.resolve(DATASTORE_STATISTICS_FILE_NAME).isFile)
        secondScope.closeDataStores()
    }

    @Test
    fun `concurrent statistics mutations serialize without lost counts`() = runTest {
        val directory = tempFolder.newFolder("statistics-concurrency")
        val scope = newDataStoreScope()
        val repository = DataStoreStatisticsRepository(directory, scope)

        coroutineScope {
            (0 until 40).map {
                async(Dispatchers.Default) {
                    repository.recordPostUrlCopy()
                }
            }.awaitAll()
        }

        assertEquals(40L, repository.observeStatistics().first().postUrlCopyCount)
        scope.closeDataStores()
    }

    @Test
    fun `statistics additions saturate and negative deltas are ignored`() = runTest {
        val repository = InMemoryStatisticsRepository(
            LifetimeStatistics(
                appOpenCount = Long.MAX_VALUE,
                totalForegroundMs = Long.MAX_VALUE - 1L,
            )
        )

        repository.recordAppOpen()
        repository.addUsageDuration(UsageDurationDelta(totalMs = 50L, browsingMs = -50L))

        val statistics = repository.observeStatistics().first()
        assertEquals(Long.MAX_VALUE, statistics.appOpenCount)
        assertEquals(Long.MAX_VALUE, statistics.totalForegroundMs)
        assertEquals(0L, statistics.browsingMs)
    }

    @Test
    fun `statistics files holding retired translation counters still load and drop them on the next write`() = runTest {
        val directory = tempFolder.newFolder("statistics-with-translation-usage")
        val file = directory.resolve(DATASTORE_STATISTICS_FILE_NAME)
        file.writeText(
            """{"schemaVersion":2,"statistics":{"appOpenCount":4,"translatedPhraseCount":3,"translatedSourceCharacterCount":18}}"""
        )
        val scope = newDataStoreScope()
        val repository = DataStoreStatisticsRepository(directory, scope)

        assertEquals(4L, repository.observeStatistics().first().appOpenCount)
        repository.recordAppOpen()
        scope.closeDataStores()

        val rewritten = file.readText()
        assertTrue(rewritten.contains("\"appOpenCount\":5"))
        assertFalse(rewritten.contains("translated"))
    }

    @Test
    fun `a history key that is not a date resets the store and keeps the original aside`() = runTest {
        val directory = tempFolder.newFolder("statistics-bad-history-key")
        directory.resolve(DATASTORE_STATISTICS_FILE_NAME).writeText(
            """{"schemaVersion":2,"statistics":{"appOpenCount":4,"history":{"not-a-day":{"appOpenCount":1}}}}"""
        )
        val scope = newDataStoreScope()
        val recoveries = FileStoreRecoveryRegistry()
        val repository = DataStoreStatisticsRepository(directory, scope, recoveryRegistry = recoveries)
        val statistics = repository.observeStatistics().first()

        assertEquals(0L, statistics.appOpenCount)
        assertRecorded(recoveries, "Statistics", directory.setAsideCopiesOf(DATASTORE_STATISTICS_FILE_NAME).single())
        scope.closeDataStores()
    }

    @Test
    fun `newer statistics schema fails closed without replacing the file`() = runTest {
        val directory = tempFolder.newFolder("statistics-future-schema")
        val destination = directory.resolve(DATASTORE_STATISTICS_FILE_NAME)
        val future = """{"schemaVersion":99,"statistics":{}}"""
        destination.writeText(future)
        val scope = newDataStoreScope()
        val repository = DataStoreStatisticsRepository(directory, scope)

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { repository.awaitReady() }
        }

        assertEquals(future, destination.readText())
        assertFalse(directory.setAsideCopiesOf(DATASTORE_STATISTICS_FILE_NAME).isNotEmpty())
        scope.closeDataStores()
    }

    private fun searched(source: SourceKey, value: String, facet: SearchFacet = SearchFacet.TAG, namespace: String? = null) =
        SearchedTagKey(source, SearchTerm(value, facet, namespace))

    private fun query(mode: QueryMode, vararg include: SearchTerm) =
        Query(mode, include.toList(), emptyList(), SortMode.NEWEST, dateRange = null, minScore = null)
}
