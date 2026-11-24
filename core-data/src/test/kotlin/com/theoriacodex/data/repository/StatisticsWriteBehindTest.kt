package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.FileStateCell
import com.theoriacodex.data.storage.closeDataStores
import com.theoriacodex.data.storage.newDataStoreScope
import com.theoriacodex.data.storage.writeBehind
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatisticsWriteBehindTest {
    @get:Rule val temporary = TemporaryFolder()

    private val writeClock = TestCoroutineScheduler()
    private val writeScope = CoroutineScope(StandardTestDispatcher(writeClock))

    @Test
    fun `many viewed posts write once with the newest counts`() = runTest {
        val writes = mutableListOf<LifetimeStatistics>()
        val repository = CellStatisticsRepository(
            FileStateCell(LifetimeStatistics()) { writes += it }.writeBehind(writeScope, STATISTICS_WRITE_DELAY),
        )
        repeat(VIEWS) { index -> repository.recordWatchedPost(SourceKey.GELBOORU, setOf("tag-$index", "shared")) }
        writeClock.runCurrent()
        assertEquals(0, writes.size)
        assertEquals(VIEWS.toLong(), repository.observeStatistics().first().watchedPostCount)

        writeClock.advanceTimeBy(STATISTICS_WRITE_DELAY.inWholeMilliseconds + 1)

        assertEquals(1, writes.size)
        assertEquals(VIEWS.toLong(), writes.single().watchedPostCount)
        assertEquals(VIEWS.toLong(), writes.single().watchedByTag.getValue(StatisticsTagKey(SourceKey.GELBOORU, "shared")))
    }

    @Test
    fun `a stop flush persists exactly what observers saw`() = runTest {
        val directory = temporary.newFolder()
        val storeScope = newDataStoreScope()
        val repository = DataStoreStatisticsRepository(directory, storeScope, writeScope = writeScope)
        repository.recordWatchedPost(SourceKey.GELBOORU, setOf(" padded ", "plain"))
        repository.recordSearch(setOf(SourceKey.GELBOORU), setOf(SearchTerm("plain")))
        repository.addUsageDuration(UsageDurationDelta(totalMs = 4_000L))
        val seen = repository.observeStatistics().first()

        repository.flush()
        storeScope.closeDataStores()

        assertEquals(seen, reopened(directory))
    }

    @Test
    fun `cancelling during the delay keeps the last written statistics readable`() = runTest {
        val directory = temporary.newFolder()
        val storeScope = newDataStoreScope()
        val repository = DataStoreStatisticsRepository(directory, storeScope, writeScope = writeScope)
        repository.recordWatchedPost(SourceKey.GELBOORU, setOf("first"))
        repository.flush()
        repository.recordWatchedPost(SourceKey.GELBOORU, setOf("second"))

        writeScope.cancel()
        writeClock.advanceTimeBy(STATISTICS_WRITE_DELAY.inWholeMilliseconds + 1)
        storeScope.closeDataStores()

        val stored = reopened(directory)
        assertEquals(1L, stored.watchedPostCount)
        assertEquals(setOf(StatisticsTagKey(SourceKey.GELBOORU, "first")), stored.watchedByTag.keys)
    }

    @Test
    fun `restored statistics are on disk before restore returns`() = runTest {
        val directory = temporary.newFolder()
        val storeScope = newDataStoreScope()
        val stoppedScope = CoroutineScope(Job().apply { cancel() })
        val repository = DataStoreStatisticsRepository(directory, storeScope, writeScope = stoppedScope)

        repository.mergeRestored(LifetimeStatistics(watchedPostCount = 7L, watchedBySource = mapOf(SourceKey.GELBOORU to 7L)))
        storeScope.closeDataStores()

        assertEquals(7L, reopened(directory).watchedPostCount)
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
    }
}
