package com.theoriacodex.app.fixtures

import com.theoriacodex.data.repository.WATCHED_TAGS_PER_SOURCE
import com.theoriacodex.data.repository.DATASTORE_STATISTICS_FILE_NAME
import com.theoriacodex.data.repository.DataStoreStatisticsRepository
import com.theoriacodex.data.repository.StatisticsTagKey
import com.theoriacodex.data.storage.closeDataStores
import com.theoriacodex.data.storage.newDataStoreScope
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgedStatisticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `the aged statistics file reopens unchanged, full at the watched tag cap, and keeps evicting as posts are viewed`() = runTest {
        val directory = temporary.newFolder()
        val today = LocalDate.now()
        AgedStatistics.write(directory, today)
        val scope = newDataStoreScope()
        try {
            val repository = DataStoreStatisticsRepository(directory, scope)
            val loaded = repository.observeStatistics().first()

            assertEquals(AgedStatistics.statistics(today), loaded)
            assertEquals(
                AgedStatistics.viewsBySource.mapValues { WATCHED_TAGS_PER_SOURCE },
                loaded.watchedByTag.keys.groupingBy(StatisticsTagKey::source).eachCount(),
            )
            assertTrue(directory.resolve(DATASTORE_STATISTICS_FILE_NAME).length() > MINIMUM_AGED_BYTES)

            val floors = AgedStatistics.postTags(VIEWED_POSTS).map { tags ->
                repository.recordWatchedPost(SourceKey.PIXIV, tags.mapTo(linkedSetOf()) { sourceTagKey(SourceKey.PIXIV, it) })
                repository.observeStatistics().first().watchedTagFloors.getValue(SourceKey.PIXIV)
            }
            val evictions = (listOf(loaded.watchedTagFloors.getValue(SourceKey.PIXIV)) + floors).zipWithNext().count { (before, after) -> after > before }
            assertTrue("evictions=$evictions", evictions >= 2)
        } finally {
            scope.closeDataStores()
        }
    }

    private companion object {
        const val MINIMUM_AGED_BYTES = 1_000_000L
        const val VIEWED_POSTS = 8
    }
}
