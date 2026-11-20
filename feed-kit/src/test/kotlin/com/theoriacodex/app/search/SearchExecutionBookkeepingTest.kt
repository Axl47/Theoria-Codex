package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchExecutionBookkeepingTest {
    @Test fun `failed provider pages stop automatic demand but retain their token for explicit Retry`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV)
        val gelbooru = TestAdapter(SourceKey.GELBOORU)
        val owner = testSearchCoordinator(TestRegistry(listOf(pixiv, gelbooru)))
        owner.initializeRoute()
        val query = unifiedQuery("continuation")
        val root = owner.executeInitial(query, SearchSourceScope.GlobalUnified) as SearchExecutionResult.Success
        gelbooru.failure = IllegalStateException("offline")
        val page = owner.executePage(root.continuation) as SearchPageResult.Success
        assertEquals("next", page.continuation.unifiedPageTokens[SourceKey.GELBOORU])
        assertFalse(page.continuation.canLoadMore)
        val failedCalls = gelbooru.searchedTags.size
        owner.executePage(page.continuation)
        assertEquals(failedCalls, gelbooru.searchedTags.size)
        gelbooru.failure = null
        val recovered = owner.retrySource(query, SearchSourceScope.GlobalUnified, page.continuation,
            SourceKey.GELBOORU) as SearchPageResult.Success
        assertTrue(recovered.continuation.failedSources.isEmpty())
    }

    @Test fun `history retains failed attempts but statistics count partial and successful empty executions only`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV)
        val gelbooru = TestAdapter(SourceKey.GELBOORU)
        val recents = InMemoryRecentsRepository()
        val statistics = InMemoryStatisticsRepository()
        val empty = object : SourceAdapter by pixiv {
            override suspend fun search(query: Query, pageToken: String?): Page<Post> =
                pixiv.search(query, pageToken).copy(items = emptyList())
        }
        val owner = testSearchCoordinator(TestRegistry(listOf(empty, gelbooru)),
            recentsRepository = recents, statisticsRepository = statistics)
        owner.initializeRoute()
        pixiv.failure = IllegalStateException("offline")
        val sourceFailed = owner.executeInitial(query(SourceKey.PIXIV, "source-failed"), SearchSourceScope.Single(SourceKey.PIXIV))
        assertFalse(sourceFailed.completedSuccessfully)
        owner.recordAcceptedSearch(sourceFailed)
        gelbooru.failure = IllegalStateException("offline")
        val allFailed = owner.executeInitial(unifiedQuery("all-failed"), SearchSourceScope.GlobalUnified)
        assertTrue(allFailed is SearchExecutionResult.Failure)
        owner.recordAcceptedSearch(allFailed)
        pixiv.failure = null
        val partial = owner.executeInitial(unifiedQuery("partial"), SearchSourceScope.GlobalUnified)
        assertTrue(partial.completedSuccessfully)
        owner.recordAcceptedSearch(partial)
        val successEmpty = owner.executeInitial(query(SourceKey.PIXIV, "empty"), SearchSourceScope.Single(SourceKey.PIXIV))
        assertTrue(successEmpty.completedSuccessfully)
        owner.recordAcceptedSearch(successEmpty)
        assertEquals(4, recents.observeSearches().first().size)
        val counters = statistics.observeStatistics().first()
        assertEquals(2L, counters.searchCount)
        assertEquals(2L, counters.searchesBySource[SourceKey.PIXIV])
        assertEquals(1L, counters.searchesBySource[SourceKey.GELBOORU])
    }
}
