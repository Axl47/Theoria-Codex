package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class SearchUndoViewModelTest : SearchViewModelTestFixture() {
    @Test fun `Undo replays grouped Multi-Search source selection and consumes history`() = runTest(mainDispatcherRule.dispatcher) {
        val adapter = ViewModelSearchAdapter()
        val owner = viewModel(adapter, additionalAdapters = listOf(ViewModelSearchAdapter(SourceKey.AIBOORU)))
        restore(owner)
        assertFalse(owner.state.value.canUndoSearch)
        val scope = SearchSourceScope.fromSources(listOf(SourceKey.PIXIV, SourceKey.AIBOORU))
        val query = Query(
            mode = QueryMode.Unified, includeTerms = listOf(SearchTerm("landscape"), SearchTerm("portrait")),
            excludeTerms = listOf(SearchTerm("rain")), sort = SortMode.POPULAR, dateRange = null, minScore = null,
            includeTermGroups = listOf(SearchTermGroup(listOf(SearchTerm("landscape"), SearchTerm("portrait")))),
        )
        owner.onAction(SearchAction.ApplyHistoricalQuery(query, scope))
        advanceUntilIdle()
        owner.onAction(SearchAction.ApplyTagSearch(listOf("second"), QueryMode.Source(SourceKey.PIXIV)))
        advanceUntilIdle()
        assertTrue(owner.state.value.canUndoSearch)
        owner.onAction(SearchAction.AutocompleteChanged("unsubmitted draft"))
        owner.onAction(SearchAction.UndoSearch)
        advanceUntilIdle()
        assertEquals(query, owner.state.value.query.applied)
        assertEquals(query, owner.state.value.query.draft)
        assertEquals(scope, owner.state.value.query.appliedSourceScope)
        assertEquals("", owner.state.value.suggestions.input)
        assertFalse(owner.state.value.canUndoSearch)
        assertTrue(owner.state.value.restoration is com.theoriacodex.app.search.state.SearchRestorationUiState.Restored)
    }

    @Test fun `refresh retries paging and stale cancelled roots do not add Undo steps`() = runTest(mainDispatcherRule.dispatcher) {
        val adapter = ViewModelSearchAdapter()
        val owner = viewModel(adapter)
        restore(owner)
        owner.onAction(SearchAction.ApplyTagSearch(listOf("paged"), QueryMode.Source(SourceKey.PIXIV)))
        advanceUntilIdle()
        owner.onAction(SearchAction.LoadNextPage)
        advanceUntilIdle()
        owner.onAction(SearchAction.Retry)
        advanceUntilIdle()
        owner.onAction(SearchAction.ApplyDraft)
        advanceUntilIdle()
        assertFalse(owner.state.value.canUndoSearch)
        owner.onAction(SearchAction.ApplyTagSearch(listOf("slow"), QueryMode.Source(SourceKey.PIXIV)))
        runCurrent()
        adapter.slowSearchStarted.await()
        owner.onAction(SearchAction.ApplyTagSearch(listOf("last"), QueryMode.Source(SourceKey.PIXIV)))
        runCurrent()
        adapter.releaseSlowSearch.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("paged"), owner.state.value.searchHistory.map { it.query.includeTags.single() })
        owner.onAction(SearchAction.UndoSearch)
        advanceUntilIdle()
        assertEquals(listOf("paged"), owner.state.value.query.applied.includeTags)
        assertFalse(owner.state.value.canUndoSearch)
    }

    @Test fun `an admitted failed search can return to the last search`() = runTest(mainDispatcherRule.dispatcher) {
        val adapter = ViewModelSearchAdapter()
        val owner = viewModel(adapter)
        restore(owner)
        owner.onAction(SearchAction.ApplyTagSearch(listOf("first"), QueryMode.Source(SourceKey.PIXIV)))
        advanceUntilIdle()
        adapter.failingTags += "broken"
        owner.onAction(SearchAction.ApplyTagSearch(listOf("broken"), QueryMode.Source(SourceKey.PIXIV)))
        advanceUntilIdle()
        assertTrue(owner.state.value.canUndoSearch)
        owner.onAction(SearchAction.UndoSearch)
        advanceUntilIdle()
        assertEquals(listOf("first-result"), resultIds(owner))
        assertFalse(owner.state.value.canUndoSearch)
    }
}
