package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class SearchSourceSwitchViewModelTest : SearchViewModelTestFixture() {
    @Test fun `switching source shows that source's last search with its own filters`() = runTest(mainDispatcherRule.dispatcher) {
        val pixiv = ViewModelSearchAdapter()
        val aibooru = ViewModelSearchAdapter(SourceKey.AIBOORU)
        val owner = viewModel(pixiv, additionalAdapters = listOf(aibooru))
        restore(owner)

        // Before anything is on screen, picking a source only prepares the draft.
        owner.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.AIBOORU)))
        advanceUntilIdle()
        assertEquals(0, pixiv.searchCount + aibooru.searchCount)

        owner.onAction(SearchAction.ApplyTagSearch(listOf(SearchTermGroup.single(SearchTerm("fox"))), mode = QueryMode.Source(SourceKey.PIXIV)))
        advanceUntilIdle()
        owner.onAction(SearchAction.SelectSort(SortMode.POPULAR))
        owner.onAction(SearchAction.ApplyDraft)
        advanceUntilIdle()
        owner.onAction(SearchAction.ApplyTagSearch(listOf(SearchTermGroup.single(SearchTerm("cat"))), mode = QueryMode.Source(SourceKey.AIBOORU)))
        advanceUntilIdle()

        owner.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
        advanceUntilIdle()

        val state = owner.state.value
        assertEquals(listOf("fox-result"), resultIds(owner))
        assertEquals(listOf("fox"), state.query.applied.includeTags)
        assertEquals(SortMode.POPULAR, state.query.applied.sort)
        assertFalse(state.hasPendingChanges)
    }
}
