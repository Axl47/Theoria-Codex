package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class SearchPageRecoveryTest : SearchViewModelTestFixture() {
    @Test fun `Retry preserves healthy results and resumes the failed page instead of restarting Search`() =
        runTest(mainDispatcherRule.dispatcher) {
            var roots = 0
            val tokens = mutableListOf<String?>()
            val owner = viewModel(ViewModelSearchAdapter(), executionService = { delegate ->
                object : SearchExecutionService by delegate {
                    override suspend fun executeInitial(query: Query, sourceScope: SearchSourceScope): SearchExecutionResult {
                        roots++
                        return delegate.executeInitial(query, sourceScope)
                    }
                    override suspend fun executePage(continuation: SearchContinuation): SearchPageResult {
                        tokens += continuation.sourcePageToken
                        return if (tokens.size == 1) SearchPageResult.Failure(continuation.executionKey, emptyList(), "offline")
                        else delegate.executePage(continuation)
                    }
                }
            })
            restore(owner)
            owner.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            owner.onAction(SearchAction.AddIncludeTerm(SearchTerm("paged")))
            owner.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()
            owner.onAction(SearchAction.LoadNextPage)
            advanceUntilIdle()
            assertEquals(listOf("paged-result"), resultIds(owner))
            owner.onAction(SearchAction.Retry)
            advanceUntilIdle()
            assertEquals(1, roots)
            assertEquals(listOf("next", "next"), tokens)
            assertEquals(listOf("paged-result", "paged-page"), resultIds(owner))
        }
}
