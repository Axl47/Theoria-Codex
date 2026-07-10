package com.theoriacodex.stubs

import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonStubSourceAdapterTest {
    @Test
    fun `loads paged fixtures in normal scenario`() = runTest {
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
    fun `partial failure scenario fails gelbooru search`() = runTest {
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
    fun `quick query maps to expected sort mode`() = runTest {
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

    @Test
    fun `rule34 family fixtures load search trending and resolve`() = runTest {
        val runtime = StubRuntime(StubScenarioPreset.NORMAL)
        val fixtureLoader = StubFixtureLoader()
        val sources = listOf(
            SourceKey.RULE34XXX,
            SourceKey.RULE34PAHEAL,
            SourceKey.RULE34VIDEO,
            SourceKey.RULE34GEN,
        )

        sources.forEach { source ->
            val adapter = JsonStubSourceAdapter(
                sourceKey = source,
                fixtureLoader = fixtureLoader,
                runtime = runtime,
            )

            val firstPage = adapter.search(sampleQuery(), pageToken = null)
            assertTrue("Expected stub search results for $source", firstPage.items.isNotEmpty())
            assertTrue("Expected stub trending tags for $source", adapter.trendingTags(limit = 2).isNotEmpty())
            assertNotNull(adapter.resolvePost(firstPage.items.first().id))
        }
    }

    @Test
    fun `iwara fixtures load search trending and resolve`() = runTest {
        val runtime = StubRuntime(StubScenarioPreset.NORMAL)
        val adapter = JsonStubSourceAdapter(
            sourceKey = SourceKey.IWARA,
            fixtureLoader = StubFixtureLoader(),
            runtime = runtime,
        )

        val firstPage = adapter.search(sampleQuery(), pageToken = null)

        assertTrue(firstPage.items.isNotEmpty())
        assertTrue(adapter.trendingTags(limit = 2).isNotEmpty())
        assertNotNull(adapter.resolvePost(firstPage.items.first().id))
    }

    @Test
    fun `hitomi fixtures load search trending paging and resolve`() = runTest {
        val runtime = StubRuntime(StubScenarioPreset.NORMAL)
        val adapter = JsonStubSourceAdapter(
            sourceKey = SourceKey.HITOMI,
            fixtureLoader = StubFixtureLoader(),
            runtime = runtime,
        )

        val firstPage = adapter.search(sampleQuery(), pageToken = null)
        val secondPage = adapter.search(sampleQuery(), pageToken = firstPage.nextPageToken)

        assertEquals("4042375", firstPage.items.single().id.sourcePostId)
        assertEquals("page_2", firstPage.nextPageToken)
        assertEquals("7231", secondPage.items.single().id.sourcePostId)
        assertTrue(adapter.trendingTags(limit = 3).any { it.type == "artist" })
        assertNotNull(adapter.resolvePost(firstPage.items.single().id))
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
