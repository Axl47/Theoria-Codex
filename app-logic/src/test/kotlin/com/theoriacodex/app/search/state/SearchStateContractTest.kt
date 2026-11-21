package com.theoriacodex.app.search.state

import com.theoriacodex.app.search.SearchInitialization
import com.theoriacodex.app.search.UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.FacetedSearchScope
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
    fun `restoring keeps the notice that stored source terms were removed`() {
        val query = emptySearchQuery().withIncludeTermsAsRequired(listOf(SearchTerm("landscape")))
        val initialization = SearchInitialization(
            query = query,
            sourceScope = SearchSourceScope.GlobalUnified,
            appliedByMode = emptyMap(),
            availableSources = listOf(SourceKey.PIXIV),
            hasExecutedSearch = true,
            validationMessage = UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE,
        )

        val restored = SearchStateReducer.restore(initialization, "hash") { emptyList() }

        assertEquals(UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE, restored.query.validationMessage)
        assertEquals(query, restored.query.draft)
        assertEquals(query, restored.query.applied)
    }

    @Test
    fun `reset availability follows clearable draft content rather than pending edits`() {
        val empty = emptySearchQuery(QueryMode.Source(SourceKey.PIXIV))
        val tagged = empty.withIncludeTermsAsRequired(listOf(SearchTerm("landscape")))
        val restored = SearchUiState(query = SearchQueryUiState(draft = tagged, applied = tagged))
        assertFalse(restored.hasPendingChanges)
        assertTrue(restored.canResetDraft)

        val cleared = SearchDraftReducer.clearDraft(restored).state
        assertTrue(cleared.hasPendingChanges)
        assertFalse(cleared.canResetDraft)
        assertTrue(cleared.copy(suggestions = SearchSuggestionsUiState(input = "sky")).canResetDraft)
        assertTrue(cleared.copy(query = cleared.query.copy(draft = empty.copy(sort = SortMode.POPULAR))).canResetDraft)
        assertFalse(SearchUiState().canResetDraft)
    }

    @Test
    fun `resolved posts replace canonical members once while preserving order and query state`() {
        val first = post("first")
        val second = post("second")
        val resolved = first.copy(title = "Resolved title")
        val state = SearchUiState(
            query = SearchQueryUiState(appliedQueryHash = "active"),
            content = SearchContentUiState(results = listOf(first, second), displayVersion = 4, canLoadMore = true),
        )

        val updated = state.withResolvedPosts("active", listOf(first.copy(title = "Earlier"), post("absent"), resolved))

        assertEquals(listOf(resolved, second), updated.content.results)
        assertEquals(5, updated.content.displayVersion)
        assertTrue(updated.content.canLoadMore)
        assertSame(state.query, updated.query)
        assertSame(state.execution, updated.execution)
        assertSame(state.restoration, updated.restoration)
    }

    @Test
    fun `stale empty unknown and equal post resolutions retain the same render state`() {
        val original = post("present")
        val state = SearchUiState(
            query = SearchQueryUiState(appliedQueryHash = "active"),
            content = SearchContentUiState(results = listOf(original)),
        )

        assertSame(state, state.withResolvedPosts("stale", listOf(original.copy(title = "Wrong query"))))
        assertSame(state, state.withResolvedPosts("active", emptyList()))
        assertSame(state, state.withResolvedPosts("active", listOf(post("absent"))))
        assertSame(state, state.withResolvedPosts("active", listOf(original.copy())))
    }

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
    fun `opening a result closes an open scroll request and a missing result leaves it open`() {
        val shown = post("shown")
        val pending = SearchRestorationUiState.Restored(false, SearchScrollState(22, 17), 1L, resetToTop = true)
        val state = SearchUiState(restoration = pending)

        val opened = SearchStateReducer.reduce(state, SearchAction.OpenResult(shown.id, listOf(shown), 0))
        val missing = SearchStateReducer.reduce(state, SearchAction.OpenResult(post("gone").id, listOf(shown), 0))

        assertTrue(opened.effects.single() is SearchEffect.OpenViewer)
        assertEquals(SearchRestorationUiState.Restored(false, null, 1L), opened.state.restoration)
        assertEquals(pending, missing.state.restoration)
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
            SearchRestorationUiState.Restored(
                restoredQuery = true,
                scrollState = SearchScrollState(2, 8),
                scrollRequestId = 1L,
            ),
            restored.restoration,
        )

        assertEquals(SearchRestorationUiState.Failed("restore failed"), failed.restoration)
        assertEquals(listOf(result), failed.content.results)
    }

    @Test
    fun `scroll restoration is one shot and stale acknowledgements cannot clear reentry requests`() {
        val restored = SearchUiState(
            restoration = SearchRestorationUiState.Restored(
                restoredQuery = true,
                scrollState = SearchScrollState(2, 8),
                scrollRequestId = 1L,
            ),
        )
        val applied = SearchStateReducer.reduce(
            restored,
            SearchStateChange.ScrollRestorationApplied(requestId = 1L),
        ).state
        val reentered = SearchStateReducer.reduce(
            applied,
            SearchStateChange.RouteEntryScrollRestorationRequested(SearchScrollState(7, 24)),
        ).state
        val staleAcknowledgement = SearchStateReducer.reduce(
            reentered,
            SearchStateChange.ScrollRestorationApplied(requestId = 1L),
        ).state
        val reapplied = SearchStateReducer.reduce(
            staleAcknowledgement,
            SearchStateChange.ScrollRestorationApplied(requestId = 2L),
        ).state

        assertEquals(
            SearchRestorationUiState.Restored(
                restoredQuery = true,
                scrollState = null,
                scrollRequestId = 1L,
            ),
            applied.restoration,
        )
        assertEquals(
            SearchRestorationUiState.Restored(
                restoredQuery = true,
                scrollState = SearchScrollState(7, 24),
                scrollRequestId = 2L,
            ),
            reentered.restoration,
        )
        assertSame(reentered, staleAcknowledgement)
        assertEquals(
            SearchRestorationUiState.Restored(
                restoredQuery = true,
                scrollState = null,
                scrollRequestId = 2L,
            ),
            reapplied.restoration,
        )
    }

    private fun query(tag: String): Query {
        return emptySearchQuery().withIncludeTermsAsRequired(listOf(SearchTerm(tag))).copy(sort = SortMode.POPULAR)
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
