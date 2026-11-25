package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class SavedSearchEditViewModelTest : SearchViewModelTestFixture() {
    private fun query(tag: String, source: SourceKey = SourceKey.PIXIV) =
        Query(QueryMode.Source(source), listOf(tag), emptyList(), SortMode.NEWEST, null, null)

    @Test fun `editing a saved search loads it as the draft, so the search on screen is pending until it runs (ADR 0218)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val owner = viewModel(ViewModelSearchAdapter())
            val effects = mutableListOf<SearchEffect>()
            backgroundScope.launch { owner.effects.collect { effects += it } }
            restore(owner)
            owner.onAction(SearchAction.ApplyTagSearch(listOf(SearchTermGroup.single(SearchTerm("other"))), mode = QueryMode.Source(SourceKey.PIXIV)))
            advanceUntilIdle()

            val saved = query("landscape")
            owner.onAction(SearchAction.EditSavedSearch("evening", saved, SearchSourceScope.fromQuery(saved)))
            assertEquals("evening", owner.state.value.editingSavedSearchId)
            assertTrue(owner.state.value.hasPendingChanges)

            advanceUntilIdle()
            assertTrue(SearchEffect.OpenComposer in effects)
            assertFalse(owner.state.value.hasPendingChanges)
            assertEquals(listOf("landscape"), owner.state.value.query.applied.includeTags)
            assertEquals("evening", owner.state.value.editingSavedSearchId)
        }

    @Test fun `a saved search Search cannot load starts no edit (ADR 0218)`() = runTest(mainDispatcherRule.dispatcher) {
        val owner = viewModel(ViewModelSearchAdapter())
        val effects = mutableListOf<SearchEffect>()
        backgroundScope.launch { owner.effects.collect { effects += it } }
        restore(owner)
        val unavailable = query("landscape", SourceKey.GELBOORU)
        owner.onAction(SearchAction.EditSavedSearch("evening", unavailable, SearchSourceScope.fromQuery(unavailable)))
        advanceUntilIdle()
        assertEquals(null, owner.state.value.editingSavedSearchId)
        assertFalse(SearchEffect.OpenComposer in effects)
    }

    @Test fun `opening another search, stepping history or ending the edit leaves edit mode (ADR 0218)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val owner = viewModel(ViewModelSearchAdapter())
            restore(owner)
            val saved = query("landscape")
            fun edit() = owner.onAction(SearchAction.EditSavedSearch("evening", saved, SearchSourceScope.fromQuery(saved)))

            edit()
            advanceUntilIdle()
            owner.onAction(SearchAction.ApplyHistoricalQuery(query("other")))
            assertEquals(null, owner.state.value.editingSavedSearchId)

            edit()
            advanceUntilIdle()
            owner.onAction(SearchAction.UndoSearch)
            assertEquals(null, owner.state.value.editingSavedSearchId)

            edit()
            advanceUntilIdle()
            owner.onAction(SearchAction.EndSavedSearchEdit)
            assertEquals(null, owner.state.value.editingSavedSearchId)
        }
}
