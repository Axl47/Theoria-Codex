package com.theoriacodex.app.search
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.domain.query.QueryHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRetryTest {
    @Test
    fun `retry source preserves other continuation and retries exact failed page`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV)
        val gelbooru = TestAdapter(SourceKey.GELBOORU)
        val owner = testSearchCoordinator(TestRegistry(listOf(pixiv, gelbooru)))
        owner.initializeRoute()
        val query = unifiedQuery("retry")
        val initial = owner.executeInitial(query, SearchSourceScope.GlobalUnified) as SearchExecutionResult.Success
        gelbooru.failure = SourceAdapterException(SourceFailureReason.NETWORK, "offline")
        val page = owner.executePage(initial.continuation) as SearchPageResult.Success
        assertEquals("next", page.continuation.unifiedPageTokens[SourceKey.GELBOORU])
        assertEquals(null, page.continuation.unifiedPageTokens[SourceKey.PIXIV])
        val successfulCalls = pixiv.searchedTags.size
        gelbooru.failure = null
        val retry = owner.retrySource(query, SearchSourceScope.GlobalUnified, page.continuation,
            SourceKey.GELBOORU) as SearchPageResult.Success
        assertEquals(successfulCalls, pixiv.searchedTags.size)
        assertEquals(listOf("retry-1"), retry.posts.map { it.id.sourcePostId })
        assertEquals(initial.executionKey, retry.executionKey)
        assertFalse(retry.continuation.canLoadMore)
    }

    @Test
    fun `retry initially failed source retains successful sources next page`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV)
        val gelbooru = TestAdapter(SourceKey.GELBOORU).apply {
            failure = SourceAdapterException(SourceFailureReason.AUTH_REQUIRED, "account")
        }
        val owner = testSearchCoordinator(TestRegistry(listOf(pixiv, gelbooru)))
        owner.initializeRoute()
        val query = unifiedQuery("retry")
        val initial = owner.executeInitial(query, SearchSourceScope.GlobalUnified) as SearchExecutionResult.Success
        gelbooru.failure = null
        val result = owner.retrySource(query, SearchSourceScope.GlobalUnified, initial.continuation,
            SourceKey.GELBOORU) as SearchPageResult.Success
        assertEquals("next", result.continuation.unifiedPageTokens[SourceKey.PIXIV])
        assertEquals("next", result.continuation.unifiedPageTokens[SourceKey.GELBOORU])
        assertEquals(1, pixiv.searchedTags.size)
        assertEquals(listOf("retry-0"), result.posts.map { it.id.sourcePostId })
    }

    @Test
    fun `single source retry preserves native facets and excludes other providers`() = runTest {
        val adapter = TestAdapter(SourceKey.NHENTAI)
        val owner = testSearchCoordinator(TestRegistry(listOf(adapter)))
        owner.initializeRoute()
        val query = query(SourceKey.NHENTAI, "").copy(includeTerms = listOf(SearchTerm("japanese", SearchFacet.LANGUAGE)))
        val result = owner.retrySource(query, SearchSourceScope.Single(SourceKey.NHENTAI), null,
            SourceKey.NHENTAI) as SearchPageResult.Success
        assertEquals(query, adapter.searchedQueries.single())
        assertEquals("next", result.continuation.sourcePageToken)
    }
}
