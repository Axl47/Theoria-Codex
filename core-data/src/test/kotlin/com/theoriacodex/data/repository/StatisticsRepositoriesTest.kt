package com.theoriacodex.data.repository

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
        repository.recordSearch(setOf(SourceKey.PIXIV, SourceKey.GELBOORU))
        repository.recordForYouSearch()
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
        assertEquals(1L, statistics.forYouSearchCount)
        assertEquals(1L, statistics.forYouSaveCount)
        assertEquals(1L, statistics.postUrlCopyCount)
        assertEquals(mapOf("codex-1" to 1L), statistics.codexEntryCounts)
    }

    @Test
    fun `statistics round trip through typed DataStore and retain source aware tags`() = runTest {
        val directory = tempFolder.newFolder("statistics-round-trip")
        val firstScope = newScope()
        val first = DataStoreStatisticsRepository(directory, firstScope)
        first.recordWatchedPost(SourceKey.PIXIV, setOf("landscape"))
        first.recordWatchedPost(SourceKey.GELBOORU, setOf("landscape"))
        first.recordSearch(setOf(SourceKey.PIXIV))
        first.addUsageDuration(UsageDurationDelta(totalMs = 12_345L, codexMs = 2_345L))
        closeScope(firstScope)

        val secondScope = newScope()
        val second = DataStoreStatisticsRepository(directory, secondScope)
        val restored = second.observeStatistics().first()

        assertEquals(2L, restored.watchedPostCount)
        assertEquals(1L, restored.watchedByTag.getValue(StatisticsTagKey(SourceKey.PIXIV, "landscape")))
        assertEquals(1L, restored.watchedByTag.getValue(StatisticsTagKey(SourceKey.GELBOORU, "landscape")))
        assertEquals(12_345L, restored.totalForegroundMs)
        assertEquals(2_345L, restored.codexMs)
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
            LifetimeStatistics(appOpenCount = Long.MAX_VALUE, totalForegroundMs = Long.MAX_VALUE - 1L)
        )

        repository.recordAppOpen()
        repository.addUsageDuration(UsageDurationDelta(totalMs = 50L, browsingMs = -50L))

        val statistics = repository.observeStatistics().first()
        assertEquals(Long.MAX_VALUE, statistics.appOpenCount)
        assertEquals(Long.MAX_VALUE, statistics.totalForegroundMs)
        assertEquals(0L, statistics.browsingMs)
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
