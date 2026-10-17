package com.theoriacodex.app.search.state

import com.theoriacodex.data.repository.SearchScrollState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScrollRestorationTest {
    private val pending = SearchRestorationUiState.Restored(false, SearchScrollState(22, 17), 1L)
    private val state = SearchUiState(restoration = pending,
        content = SearchContentUiState(hasExecutedSearch = true, canLoadMore = true))

    @Test fun `missing anchor waits for continuation and exhaustion permits empty fallback`() {
        assertFalse(state.canApplyScrollRestoration)
        assertTrue(state.copy(content = state.content.copy(canLoadMore = false)).canApplyScrollRestoration)
    }

    @Test fun `loading and failure are not evidence of exhaustion`() {
        val exhausted = state.copy(content = state.content.copy(canLoadMore = false))
        for (kind in listOf(SearchRequestKind.INITIAL, SearchRequestKind.PAGE)) {
            assertFalse(exhausted.copy(execution = SearchExecutionUiState(activeKind = kind)).canApplyScrollRestoration)
        }
        assertFalse(exhausted.copy(content = exhausted.content.copy(hasExecutedSearch = false)).canApplyScrollRestoration)
        assertFalse(exhausted.copy(content = exhausted.content.copy(
            error = SearchErrorUiState("Retry", SearchRequestKind.PAGE, true),
        )).canApplyScrollRestoration)
    }

    @Test fun `only acknowledged restoration allows scroll persistence`() {
        assertFalse(SearchRestorationUiState.NotStarted.allowsScrollPersistence)
        assertFalse(SearchRestorationUiState.Restoring.allowsScrollPersistence)
        assertFalse(SearchRestorationUiState.Failed("Retry").allowsScrollPersistence)
        assertFalse(pending.allowsScrollPersistence)
        assertTrue(pending.copy(scrollState = null).allowsScrollPersistence)
    }
}
