package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.FileStateCell
import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.data.storage.MemoryStateCell
import com.theoriacodex.data.storage.closeDataStores
import com.theoriacodex.data.storage.newDataStoreScope
import com.theoriacodex.data.storage.WRITE_BEHIND_DELAY
import com.theoriacodex.data.storage.WriteBehind
import com.theoriacodex.data.storage.writeBehind
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatisticsWriteBehindTest {
    @get:Rule val temporary = TemporaryFolder()

    private val writeClock = TestCoroutineScheduler()
    private val writeScope = CoroutineScope(StandardTestDispatcher(writeClock))

    @Test
    fun `many viewed posts write once with the newest counts (ADR 0215)`() = runTest {
        val writes = mutableListOf<LifetimeStatistics>()
        val repository = CellStatisticsRepository(
            FileStateCell(LifetimeStatistics()) { writes += it }.writeBehind(WriteBehind(writeScope)),
        )
        repeat(VIEWS) { index -> repository.recordWatchedPost(SourceKey.GELBOORU, setOf("tag-$index", "shared")) }
        writeClock.runCurrent()
        assertEquals(0, writes.size)
        assertEquals(VIEWS.toLong(), repository.observeStatistics().first().watchedPostCount)

        writeClock.advanceTimeBy(WRITE_BEHIND_DELAY.inWholeMilliseconds + 1)

        assertEquals(1, writes.size)
        assertEquals(VIEWS.toLong(), writes.single().watchedPostCount)
        assertEquals(VIEWS.toLong(), writes.single().watchedByTag.getValue(StatisticsTagKey(SourceKey.GELBOORU, "shared")))
    }

    @Test
    fun `a stop flush persists exactly what observers saw (ADR 0215)`() = runTest {
        val directory = temporary.newFolder()
        val storeScope = newDataStoreScope()
        val repository = DataStoreStatisticsRepository(directory, storeScope, writeBehind = WriteBehind(writeScope))
        repository.recordWatchedPost(SourceKey.GELBOORU, setOf(" padded ", "plain"))
        repository.recordSearch(setOf(SourceKey.GELBOORU), setOf(SearchTerm("plain")))
        repository.addUsageDuration(UsageDurationDelta(totalMs = 4_000L))
        val seen = repository.observeStatistics().first()

        repository.flush()
        storeScope.closeDataStores()

        assertEquals(seen, reopened(directory))
    }

    @Test
    fun `the statistics store writes values without a decode-encode round trip (ADR 0215)`() = runTest {
        val directory = temporary.newFolder()
        val storeScope = newDataStoreScope()
        val store = openStatisticsDataStore(directory, storeScope, FileStoreRecoveryRegistry())
        val repository = CellStatisticsRepository(store.writeBehind(WriteBehind(writeScope)))
        repository.recordWatchedPost(SourceKey.GELBOORU, setOf("plain"))
        repository.recordSearch(setOf(SourceKey.GELBOORU), setOf(SearchTerm("plain")))
        val seen = repository.observeStatistics().first()

        repository.flush()

        assertSame(seen, store.value())
        storeScope.closeDataStores()
        assertEquals(seen, reopened(directory))
    }

    @Test
    fun `normalized statistics survive the file encoding unchanged (ADR 0215)`() = runTest {
        val custom = SourceKey.parseOrThrow("custom_example_com")
        val repository = CellStatisticsRepository(MemoryStateCell(LifetimeStatistics()))
        repository.recordAppOpen()
        repository.addUsageDuration(UsageDurationDelta(totalMs = 9_000L, browsingMs = 4_000L, readingMs = 2_000L))
        repository.recordWatchedPost(SourceKey.GELBOORU, setOf("Plain", " padded ", "shared"), CodexPostOpen("codex-a", "post-1", "First"))
        repository.recordWatchedPost(custom, setOf("shared"))
        repeat(TAGGED_VIEWS) { index -> repository.recordWatchedPost(SourceKey.PIXIV, setOf("tag-$index", "tag-${index / 2}")) }
        repository.recordSearch(
            setOf(SourceKey.GELBOORU, custom),
            setOf(
                SearchTerm("Plain"),
                SearchTerm("Some Artist", SearchFacet.ARTIST),
                SearchTerm("meta", SearchFacet.TAG, " Meta "),
                SearchTerm("Hero", SearchFacet.CHARACTER),
            ),
        )
        repository.addCodexUsage(CodexUsageTarget("codex-a"), 3_000L)
        repository.recordCodexEntry(" codex-b ")
        repository.recordForYouSave()
        repository.recordPostUrlCopy()
        val recorded = repository.observeStatistics().first()
        val normalized = StatisticsPolicies.normalize(recorded.copy(watchedTagFloors = mapOf(SourceKey.PIXIV to 1L)))

        assertEquals(normalized, StatisticsDataStoreFile.fromDomain(normalized).toDomain())
    }

    @Test
    fun `cancelling during the delay keeps the last written statistics readable (ADR 0215)`() = runTest {
        val directory = temporary.newFolder()
        val storeScope = newDataStoreScope()
        val repository = DataStoreStatisticsRepository(directory, storeScope, writeBehind = WriteBehind(writeScope))
        repository.recordWatchedPost(SourceKey.GELBOORU, setOf("first"))
        repository.flush()
        repository.recordWatchedPost(SourceKey.GELBOORU, setOf("second"))

        writeScope.cancel()
        writeClock.advanceTimeBy(WRITE_BEHIND_DELAY.inWholeMilliseconds + 1)
        storeScope.closeDataStores()

        val stored = reopened(directory)
        assertEquals(1L, stored.watchedPostCount)
        assertEquals(setOf(StatisticsTagKey(SourceKey.GELBOORU, "first")), stored.watchedByTag.keys)
    }

    @Test
    fun `restored statistics are on disk before restore returns (ADR 0215)`() = runTest {
        val directory = temporary.newFolder()
        val storeScope = newDataStoreScope()
        val stoppedScope = CoroutineScope(Job().apply { cancel() })
        val repository = DataStoreStatisticsRepository(directory, storeScope, writeBehind = WriteBehind(stoppedScope))

        repository.mergeRestored(LifetimeStatistics(watchedPostCount = 7L, watchedBySource = mapOf(SourceKey.GELBOORU to 7L)))
        storeScope.closeDataStores()

        assertEquals(7L, reopened(directory).watchedPostCount)
    }

    @Test
    fun `a backup with recents saves pending positions and statistics first (ADR 0215)`() = runTest {
        val directory = temporary.newFolder()
        val storeScope = newDataStoreScope()
        val stoppedScope = CoroutineScope(Job().apply { cancel() })
        val positions = FileBackedReadingPositionRepository(directory, writeBehind = WriteBehind(stoppedScope))
        val statistics = DataStoreStatisticsRepository(directory, storeScope, writeBehind = WriteBehind(stoppedScope))
        positions.record(ReadingPosition(PostId(SourceKey.PIXIV, "book"), RecentPostSection.WATCHED, 4, 400L))
        statistics.recordWatchedPost(SourceKey.PIXIV, setOf("tag"))

        ProfileBackupService(
            InMemorySettingsRepository(), FakeBackupStore(), InMemorySavedSearchRepository(), positions,
            directory.resolve("restore.json"), statistics = statistics,
        ).export(includeRecents = true)
        storeScope.closeDataStores()

        assertEquals(4, FileBackedReadingPositionRepository(directory).snapshot().single().mediaNumber)
        assertEquals(1L, reopened(directory).watchedPostCount)
    }

    private suspend fun reopened(directory: java.io.File): LifetimeStatistics {
        val scope = newDataStoreScope()
        return try {
            DataStoreStatisticsRepository(directory, scope).observeStatistics().first()
        } finally {
            scope.closeDataStores()
        }
    }

    private companion object {
        const val VIEWS = 50
        const val TAGGED_VIEWS = 40
    }
}
