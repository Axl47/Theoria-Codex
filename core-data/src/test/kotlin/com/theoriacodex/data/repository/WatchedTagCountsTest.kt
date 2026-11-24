package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.closeDataStores
import com.theoriacodex.data.storage.newDataStoreScope
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchedTagCountsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val gelbooru = SourceKey.GELBOORU

    @Test
    fun `a new tag at a full source evicts the lowest count and starts one above it (ADR 0030)`() {
        val full = LifetimeStatistics(watchedByTag = counts(gelbooru, "a" to 5L, "b" to 3L, "c" to 7L))

        val next = full.recordWatchedTags(gelbooru, setOf("d"), limit = 3)

        assertEquals(counts(gelbooru, "a" to 5L, "c" to 7L, "d" to 4L), next.watchedByTag)
        assertEquals(mapOf(gelbooru to 3L), next.watchedTagFloors)
    }

    @Test
    fun `tied lowest counts leave together and a tag seen again counts on from its estimate (ADR 0030)`() {
        val full = LifetimeStatistics(watchedByTag = counts(gelbooru, "a" to 2L, "b" to 2L, "c" to 9L))

        val evicted = full.recordWatchedTags(gelbooru, setOf("d"), limit = 3)
        val back = evicted.recordWatchedTags(gelbooru, setOf("a"), limit = 3)

        assertEquals(counts(gelbooru, "c" to 9L, "d" to 3L), evicted.watchedByTag)
        assertEquals(counts(gelbooru, "a" to 3L, "c" to 9L, "d" to 3L), back.watchedByTag)
        assertEquals(mapOf(gelbooru to 2L), back.watchedTagFloors)
    }

    @Test
    fun `a view counts the tags already held before adding new ones, whatever their order (ADR 0030)`() {
        val full = LifetimeStatistics(watchedByTag = counts(gelbooru, "a" to 5L, "b" to 10L))

        val newFirst = full.recordWatchedTags(gelbooru, linkedSetOf("c", "a"), limit = 2)

        assertEquals(counts(gelbooru, "b" to 10L, "c" to 7L), newFirst.watchedByTag)
        assertEquals(mapOf(gelbooru to 6L), newFirst.watchedTagFloors)
        assertEquals(newFirst, full.recordWatchedTags(gelbooru, linkedSetOf("a", "c"), limit = 2))
        val two = LifetimeStatistics(watchedByTag = counts(gelbooru, "a" to 1L, "b" to 10L))
        assertEquals(two.recordWatchedTags(gelbooru, linkedSetOf("d", "e"), limit = 2), two.recordWatchedTags(gelbooru, linkedSetOf("e", "d"), limit = 2))
    }

    @Test
    fun `eviction at one source leaves every other source's counts and floor alone`() {
        val pixiv = counts(SourceKey.PIXIV, "sky" to 3L, "sea" to 2L)
        val statistics = LifetimeStatistics(
            watchedByTag = counts(gelbooru, "a" to 4L, "b" to 6L) + pixiv,
            watchedTagFloors = mapOf(SourceKey.PIXIV to 1L),
        )

        val next = statistics.recordWatchedTags(gelbooru, setOf("c"), limit = 2)

        assertEquals(pixiv, next.watchedByTag.filterKeys { it.source == SourceKey.PIXIV })
        assertEquals(mapOf(SourceKey.PIXIV to 1L, gelbooru to 4L), next.watchedTagFloors)
    }

    @Test
    fun `an uncapped table keeps its top counts and floors at the largest count that did not fit (ADR 0030)`() {
        val uncapped = LifetimeStatistics(
            watchedByTag = counts(gelbooru, "a" to 9L, "b" to 7L, "c" to 4L, "d" to 4L, "e" to 1L) + counts(SourceKey.PIXIV, "sky" to 1L),
        )

        val capped = uncapped.capWatchedTags(limit = 3)

        assertEquals(counts(gelbooru, "a" to 9L, "b" to 7L, "c" to 4L) + counts(SourceKey.PIXIV, "sky" to 1L), capped.watchedByTag)
        assertEquals(mapOf(gelbooru to 4L), capped.watchedTagFloors)
        assertEquals(capped, capped.capWatchedTags(limit = 3))
        val tied = LifetimeStatistics(watchedByTag = counts(gelbooru, "z" to 1L, "y" to 1L, "x" to 1L)).capWatchedTags(limit = 2)
        assertEquals(counts(gelbooru, "x" to 1L, "y" to 1L), tied.watchedByTag)
        assertEquals(mapOf(gelbooru to 1L), tied.watchedTagFloors)
    }

    @Test
    fun `restoring a backup reads absent tags at each side's floor and changes nothing the second time (ADR 0030)`() {
        val current = LifetimeStatistics(
            watchedByTag = counts(gelbooru, "a" to 12L, "b" to 3L),
            watchedTagFloors = mapOf(gelbooru to 2L),
        )
        val backup = LifetimeStatistics(
            watchedByTag = counts(gelbooru, "a" to 8L, "c" to 9L),
            watchedTagFloors = mapOf(gelbooru to 5L),
        )

        val once = StatisticsPolicies.normalize(StatisticsPolicies.mergeRestored(current, backup))

        assertEquals(counts(gelbooru, "a" to 12L, "c" to 9L), once.watchedByTag)
        assertEquals(mapOf(gelbooru to 5L), once.watchedTagFloors)
        assertEquals(once, StatisticsPolicies.normalize(StatisticsPolicies.mergeRestored(once, backup)))
    }

    @Test
    fun `a version 2 file with an uncapped map loads capped and is written back as version 3 with its floor (ADR 0030)`() = runTest {
        val directory = tempFolder.newFolder("statistics-v2")
        val tags = (1..WATCHED_TAGS_PER_SOURCE + 1).map { StatisticsTagCountRecord("GELBOORU", "tag$it", it.toLong()) }
        val v2 = StatisticsDataStoreFile(
            schemaVersion = 2,
            statistics = StatisticsStoreRecord(watchedBySource = mapOf("GELBOORU" to 9_000L), watchedByTag = tags),
        )
        directory.resolve(DATASTORE_STATISTICS_FILE_NAME).writeText(DurableJson.encodeToString(StatisticsDataStoreFile.serializer(), v2))
        val scope = newDataStoreScope()
        try {
            val repository = DataStoreStatisticsRepository(directory, scope)
            val loaded = repository.observeStatistics().first()
            assertEquals(WATCHED_TAGS_PER_SOURCE, loaded.watchedByTag.size)
            assertNull(loaded.watchedByTag[StatisticsTagKey(gelbooru, "tag1")])
            assertEquals(2L, loaded.watchedByTag[StatisticsTagKey(gelbooru, "tag2")])
            assertEquals(mapOf(gelbooru to 1L), loaded.watchedTagFloors)

            repository.recordWatchedPost(gelbooru, setOf("tag2"))

            val stored = DurableJson.parseToJsonElement(directory.resolve(DATASTORE_STATISTICS_FILE_NAME).readText()).jsonObject
            assertEquals(STATISTICS_DATASTORE_SCHEMA_VERSION, stored.getValue("schemaVersion").jsonPrimitive.int)
            val written = DurableJson.decodeFromJsonElement(StatisticsDataStoreFile.serializer(), stored).toDomain()
            assertEquals(mapOf(gelbooru to 1L), written.watchedTagFloors)
            assertEquals(3L, written.watchedByTag[StatisticsTagKey(gelbooru, "tag2")])
        } finally {
            scope.closeDataStores()
        }
    }

    private fun counts(source: SourceKey, vararg entries: Pair<String, Long>): Map<StatisticsTagKey, Long> =
        entries.associate { (tag, count) -> StatisticsTagKey(source, tag) to count }
}
