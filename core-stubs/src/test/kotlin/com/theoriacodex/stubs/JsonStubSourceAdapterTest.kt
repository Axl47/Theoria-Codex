package com.theoriacodex.stubs

import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonStubSourceAdapterTest {
    @Test
    fun `loads paged fixtures in normal scenario`() = runBlocking {
        val runtime = StubRuntime(StubScenarioPreset.NORMAL)
        val adapter = JsonStubSourceAdapter(
            sourceKey = SourceKey.PIXIV,
            fixtureLoader = StubFixtureLoader(),
            runtime = runtime,
        )

        val first = adapter.search(sampleQuery(), pageToken = null)
        val second = adapter.search(sampleQuery(), pageToken = first.nextPageToken)

        assertEquals(2, first.items.size)
        assertEquals("page_2", first.nextPageToken)
        assertEquals(1, second.items.size)
        assertEquals(null, second.nextPageToken)
    }

    @Test
    fun `partial failure scenario fails gelbooru search`() = runBlocking {
        val runtime = StubRuntime(StubScenarioPreset.PARTIAL_FAILURE)
        val adapter = JsonStubSourceAdapter(
            sourceKey = SourceKey.GELBOORU,
            fixtureLoader = StubFixtureLoader(),
            runtime = runtime,
        )

        val failure = runCatching {
            adapter.search(sampleQuery(), pageToken = null)
        }

        assertTrue(failure.isFailure)
    }

    @Test
    fun `quick query maps to expected sort mode`() = runBlocking {
        val runtime = StubRuntime(StubScenarioPreset.NORMAL)
        val adapter = JsonStubSourceAdapter(
            sourceKey = SourceKey.AIBOORU,
            fixtureLoader = StubFixtureLoader(),
            runtime = runtime,
        )

        val query = adapter.quickQuery(QuickQueryKind.RANDOM)

        assertEquals(SortMode.RANDOM, query.sort)
        assertEquals(QueryMode.Source(SourceKey.AIBOORU), query.mode)
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Unified,
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }
}
