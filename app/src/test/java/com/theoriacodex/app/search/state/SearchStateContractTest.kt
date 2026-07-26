package com.theoriacodex.app.search.state

import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.query.QueryHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchStateContractTest {
    @Test
    fun `initial state is idle platform-free and ready for draft input`() {
        val state = SearchUiState()

        assertEquals(QueryMode.Unified, state.query.draft.mode)
        assertEquals(state.query.applied, state.query.draft)
        assertTrue(state.content.results.isEmpty())
        assertFalse(state.loading)
        assertFalse(state.loadingMore)
        assertFalse(state.hasPendingChanges)
        assertEquals(SearchRestorationUiState.NotStarted, state.restoration)
    }

    @Test
    fun `source scope distinguishes global single and canonical temporary selections`() {
        assertEquals(
            SearchSourceScope.GlobalUnified,
            SearchSourceScope.fromSources(emptyList()),
        )
        assertEquals(
            SearchSourceScope.Single(SourceKey.PIXIV),
            SearchSourceScope.fromSources(listOf(SourceKey.PIXIV)),
        )
        assertEquals(
            SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV)),
            SearchSourceScope.fromSources(listOf(SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.PIXIV)),
        )
    }

    @Test
    fun `selection-only edits count as pending changes`() {
        val applied = query("same")
        val state = SearchUiState(
            query = SearchQueryUiState(
                draft = applied,
                applied = applied,
                draftSourceScope = SearchSourceScope.Temporary(
                    listOf(SourceKey.GELBOORU, SourceKey.PIXIV),
                ),
                appliedSourceScope = SearchSourceScope.GlobalUnified,
            ),
        )

        assertTrue(state.hasPendingChanges)
    }

    @Test
    fun `coordinator snapshot maps all renderable search lanes`() {
        val applied = query("applied")
        val draft = query("draft")
        val result = post("mapped")
        val status = SourceRunStatus(SourceKey.PIXIV, SourceRunState.SUCCESS)
        val mutableResults = mutableListOf(result)
        val mutableStatuses = mutableListOf(status)
        val scope = FacetedSearchScope(SearchFacet.ARTIST, "artist")
        val snapshot = SearchCoordinatorSnapshot(
            draftQuery = draft,
            appliedQuery = applied,
            draftSourceScope = SearchSourceScope.Temporary(
                listOf(SourceKey.GELBOORU, SourceKey.PIXIV),
            ),
            appliedSourceScope = SearchSourceScope.GlobalUnified,
            appliedQueryHash = QueryHash.from(applied),
            results = mutableResults,
            statuses = mutableStatuses,
            trendingTags = listOf(TagSuggestion("popular", "trending", 10)),
            autocompleteSuggestions = listOf(TagSuggestion("plain", "tag", 5)),
            facetedAutocompleteSuggestions = listOf(
                FacetedTagSuggestion("artist name", SearchFacet.ARTIST, "artist", 2),
            ),
            selectedSearchScope = scope,
            supportedSearchScopes = listOf(FacetedSearchScope.All, scope),
            availableSources = listOf(SourceKey.PIXIV),
            modeOptions = listOf(QueryMode.Unified, QueryMode.Source(SourceKey.PIXIV)),
            enabledSourceCount = 1,
            tagInputValidationMessage = "validation",
            loading = true,
            loadingMore = false,
            canLoadMore = true,
            errorMessage = "temporary failure",
            displayResultsVersion = 7,
            hasAnySearchRun = true,
        )

        val state = snapshot.toSearchUiState(
            restoration = SearchRestorationUiState.Restored(
                restoredQuery = true,
                scrollState = SearchScrollState(3, 12),
            ),
        )
        mutableResults.clear()
        mutableStatuses.clear()

        assertEquals(draft, state.query.draft)
        assertEquals(applied, state.query.applied)
        assertEquals(
            SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV)),
            state.query.draftSourceScope,
        )
        assertEquals(SearchSourceScope.GlobalUnified, state.query.appliedSourceScope)
        assertEquals(listOf(result), state.content.results)
        assertEquals(listOf(status), state.content.statuses)
        assertEquals(scope, state.query.selectedScope)
        assertEquals("validation", state.query.validationMessage)
        assertEquals(1, state.suggestions.trending.size)
        assertEquals(1, state.suggestions.autocomplete.size)
        assertEquals(1, state.suggestions.facetedAutocomplete.size)
        assertEquals(SearchRequestKind.REPLACE, state.execution.activeKind)
        assertEquals("temporary failure", state.content.error?.message)
        assertTrue(state.hasPendingChanges)
        assertTrue(state.loading)
    }

    @Test
    fun `begin loading clears old error while retaining visible content`() {
        val existing = post("existing")
        val state = SearchUiState(
            content = SearchContentUiState(
                results = listOf(existing),
                error = SearchErrorUiState("old", SearchRequestKind.RETRY, retryable = true),
            ),
        )
        val submitted = query("new")

        val reduced = SearchStateReducer.reduce(
            state,
            SearchStateChange.BeginRequest(10L, SearchRequestKind.REPLACE, submitted),
        ).state

        assertEquals(listOf(existing), reduced.content.results)
        assertNull(reduced.content.error)
        assertEquals(10L, reduced.execution.activeRequestId)
        assertEquals(submitted, reduced.execution.submittedQuery)
        assertTrue(reduced.loading)
    }

    @Test
    fun `replacement atomically applies query results statuses and paging state`() {
        val submitted = query("replacement")
        val loading = SearchStateReducer.reduce(
            SearchUiState(query = SearchQueryUiState(draft = submitted)),
            SearchStateChange.BeginRequest(11L, SearchRequestKind.REPLACE, submitted),
        ).state
        val status = SourceRunStatus(SourceKey.GELBOORU, SourceRunState.SUCCESS)
        val replacement = listOf(post("one"), post("two"))

        val completed = SearchStateReducer.reduce(
            loading,
            SearchStateChange.ReplaceResults(
                requestId = 11L,
                appliedQuery = submitted,
                results = replacement,
                statuses = listOf(status),
                canLoadMore = true,
            ),
        ).state

        assertEquals(submitted, completed.query.applied)
        assertEquals(submitted, completed.query.draft)
        assertEquals(QueryHash.from(submitted), completed.query.appliedQueryHash)
        assertEquals(replacement, completed.content.results)
        assertEquals(listOf(status), completed.content.statuses)
        assertTrue(completed.content.canLoadMore)
        assertTrue(completed.content.hasExecutedSearch)
        assertEquals(1, completed.content.displayVersion)
        assertEquals(11L, completed.execution.lastCompletedRequestId)
        assertFalse(completed.loading)
    }

    @Test
    fun `replacement does not overwrite draft edits made after submission`() {
        val submitted = query("submitted")
        val edited = query("edited while loading")
        val loading = SearchStateReducer.reduce(
            SearchUiState(query = SearchQueryUiState(draft = submitted)),
            SearchStateChange.BeginRequest(12L, SearchRequestKind.REPLACE, submitted),
        ).state.copy(query = SearchQueryUiState(draft = edited))

        val completed = SearchStateReducer.reduce(
            loading,
            SearchStateChange.ReplaceResults(12L, submitted, emptyList(), emptyList(), false),
        ).state

        assertEquals(submitted, completed.query.applied)
        assertEquals(edited, completed.query.draft)
        assertTrue(completed.hasPendingChanges)
    }

    @Test
    fun `retry error retains prior results and records retry context`() {
        val existing = post("retained")
        val initial = SearchUiState(
            content = SearchContentUiState(results = listOf(existing), hasExecutedSearch = true),
        )
        val retrying = SearchStateReducer.reduce(
            initial,
            SearchStateChange.BeginRequest(13L, SearchRequestKind.RETRY, initial.query.applied),
        ).state

        val failed = SearchStateReducer.reduce(
            retrying,
            SearchStateChange.RequestFailed(13L, "network unavailable", retryable = true),
        ).state

        assertEquals(listOf(existing), failed.content.results)
        assertEquals("network unavailable", failed.content.error?.message)
        assertEquals(SearchRequestKind.RETRY, failed.content.error?.requestKind)
        assertTrue(failed.content.error?.retryable == true)
        assertNull(failed.execution.activeRequestId)
    }

    @Test
    fun `paging appends unique posts and preserves ordered existing results`() {
        val first = post("first")
        val duplicate = post("duplicate")
        val next = post("next")
        val initial = SearchUiState(
            content = SearchContentUiState(
                results = listOf(first, duplicate),
                hasExecutedSearch = true,
                canLoadMore = true,
            ),
        )
        val paging = SearchStateReducer.reduce(
            initial,
            SearchStateChange.BeginRequest(14L, SearchRequestKind.PAGE, initial.query.applied),
        ).state

        val completed = SearchStateReducer.reduce(
            paging,
            SearchStateChange.AppendPage(
                requestId = 14L,
                results = listOf(duplicate, next),
                statuses = emptyList(),
                canLoadMore = false,
            ),
        ).state

        assertEquals(listOf(first.id, duplicate.id, next.id), completed.content.results.map(Post::id))
        assertFalse(completed.content.canLoadMore)
        assertEquals(14L, completed.execution.lastCompletedRequestId)
        assertFalse(completed.loadingMore)
    }

    @Test
    fun `stale completion and cancellation cannot replace the active generation`() {
        val firstRequest = SearchStateReducer.reduce(
            SearchUiState(),
            SearchStateChange.BeginRequest(20L, SearchRequestKind.REPLACE, query("old")),
        ).state
        val current = SearchStateReducer.reduce(
            firstRequest,
            SearchStateChange.BeginRequest(21L, SearchRequestKind.REPLACE, query("current")),
        ).state

        val afterStaleResult = SearchStateReducer.reduce(
            current,
            SearchStateChange.ReplaceResults(20L, query("old"), listOf(post("stale")), emptyList(), false),
        ).state
        val afterStaleCancel = SearchStateReducer.reduce(
            afterStaleResult,
            SearchStateChange.RequestCancelled(20L),
        ).state

        assertSame(current, afterStaleResult)
        assertSame(afterStaleResult, afterStaleCancel)
        assertEquals(21L, afterStaleCancel.execution.activeRequestId)

        val cancelled = SearchStateReducer.reduce(
            afterStaleCancel,
            SearchStateChange.RequestCancelled(21L),
        ).state
        assertNull(cancelled.execution.activeRequestId)
        assertEquals(21L, cancelled.execution.lastCancelledRequestId)
        assertNull(cancelled.content.error)
    }

    @Test
    fun `viewer action emits navigation context without platform handles`() {
        val first = post("viewer-one")
        val second = post("viewer-two")
        val applied = query("viewer")
        val state = SearchUiState(
            query = SearchQueryUiState(
                draft = applied,
                applied = applied,
                appliedQueryHash = QueryHash.from(applied),
            ),
            content = SearchContentUiState(results = listOf(first, second)),
        )

        val reduction = SearchStateReducer.reduce(
            state,
            SearchAction.OpenResult(
                postId = second.id,
                visibleResults = listOf(first, second),
                scrollOffsetHint = 44,
            ),
        )

        val effect = reduction.effects.single() as SearchEffect.OpenViewer
        assertEquals(listOf(first, second), effect.posts)
        assertEquals(1, effect.context.startIndex)
        assertEquals(44, effect.context.scrollOffsetHint)
        assertEquals(QueryHash.from(applied), effect.context.queryHash)
        assertEquals(ViewerStreamSource.SEARCH, effect.context.streamSource)
    }

    @Test
    fun `restoration transitions are explicit and preserve search content`() {
        val result = post("restored")
        val initial = SearchUiState(content = SearchContentUiState(results = listOf(result)))

        val restoring = SearchStateReducer.reduce(
            initial,
            SearchStateChange.BeginRestoration,
        ).state
        val restored = SearchStateReducer.reduce(
            restoring,
            SearchStateChange.RestorationCompleted(
                restoredQuery = true,
                scrollState = SearchScrollState(2, 8),
            ),
        ).state
        val failed = SearchStateReducer.reduce(
            restored,
            SearchStateChange.RestorationFailed("restore failed"),
        ).state

        assertEquals(SearchRestorationUiState.Restoring, restoring.restoration)
        assertEquals(
            SearchRestorationUiState.Restored(true, SearchScrollState(2, 8)),
            restored.restoration,
        )
        assertEquals(SearchRestorationUiState.Failed("restore failed"), failed.restoration)
        assertEquals(listOf(result), failed.content.results)
    }

    private fun query(tag: String): Query {
        return emptySearchQuery().copy(
            includeTerms = listOf(SearchTerm(tag)),
            sort = SortMode.POPULAR,
        )
    }

    private fun post(id: String): Post {
        return Post(
            id = PostId(SourceKey.PIXIV, id),
            preview = ImageRef(
                url = "https://example.test/$id.jpg",
                localPath = null,
                mime = "image/jpeg",
            ),
            full = null,
            pageUrl = null,
            width = 100,
            height = 100,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )
    }
}
