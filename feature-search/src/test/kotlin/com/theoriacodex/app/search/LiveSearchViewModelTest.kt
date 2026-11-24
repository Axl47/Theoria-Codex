package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.QueryRepository
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class LiveSearchViewModelTest : SearchViewModelTestFixture() {
    private val recents = InMemoryRecentsRepository()

    @Test fun `an edit runs after the single-source pause, and a newer edit restarts it`() = runTest(mainDispatcherRule.dispatcher) {
        val adapter = ViewModelSearchAdapter()
        val owner = searched(adapter, "a")
        val before = adapter.searchCount
        owner.onAction(SearchAction.AddIncludeTerm(SearchTerm("b")))
        advanceTimeBy(400); runCurrent()
        assertTrue(owner.state.value.execution.liveSearchWaiting)
        assertTrue(owner.state.value.replacingResults)
        owner.onAction(SearchAction.AddIncludeTerm(SearchTerm("c")))
        advanceTimeBy(500); runCurrent()
        assertEquals(before, adapter.searchCount)
        advanceTimeBy(101); runCurrent()
        advanceUntilIdle()
        assertEquals(before + 1, adapter.searchCount)
        assertEquals(listOf("a", "b", "c"), owner.state.value.query.applied.includeTags)
        assertFalse(owner.state.value.execution.liveSearchWaiting)
    }

    @Test fun `a search across several sources waits longer`() = runTest(mainDispatcherRule.dispatcher) {
        val adapter = ViewModelSearchAdapter()
        val owner = viewModel(adapter, additionalAdapters = listOf(ViewModelSearchAdapter(SourceKey.AIBOORU)))
        restore(owner)
        owner.onAction(SearchAction.ApplyTagSearch(listOf(SearchTermGroup.single(SearchTerm("a")))))
        advanceUntilIdle()
        val before = adapter.searchCount
        owner.onAction(SearchAction.AddIncludeTerm(SearchTerm("b")))
        advanceTimeBy(700); runCurrent()
        assertEquals(before, adapter.searchCount)
        advanceTimeBy(201); runCurrent()
        advanceUntilIdle()
        assertEquals(before + 1, adapter.searchCount)
    }

    @Test fun `a newer edit cancels the request in flight and the results stay until replaced`() = runTest(mainDispatcherRule.dispatcher) {
        val adapter = ViewModelSearchAdapter()
        val owner = searched(adapter, "base")
        owner.onAction(SearchAction.RestoreDraftTerms(listOf(SearchTermGroup.single(SearchTerm("slow"))), emptyList()))
        advanceTimeBy(601); runCurrent()
        adapter.slowSearchStarted.await()
        assertEquals(listOf("base-result"), resultIds(owner))
        assertTrue(owner.state.value.retainsResultsWhileRefreshing)

        owner.onAction(SearchAction.RestoreDraftTerms(listOf(SearchTermGroup.single(SearchTerm("fast"))), emptyList()))
        assertNull(owner.state.value.execution.request)
        adapter.releaseSlowSearch.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("fast-result"), resultIds(owner))
        assertEquals(listOf("fast"), owner.state.value.query.applied.includeTags)
    }

    @Test fun `live searches leave no trace until one settles when the composer closes`() = runTest(mainDispatcherRule.dispatcher) {
        val owner = searched(ViewModelSearchAdapter(), "a")
        liveEdit(owner, "b")
        liveEdit(owner, "c")
        assertEquals(listOf("a"), recordedSearches())
        assertTrue(owner.state.value.history.back.isEmpty())

        owner.onAction(SearchAction.SettleSearch)
        advanceUntilIdle()
        assertEquals(listOf("a+b+c", "a"), recordedSearches())
        assertEquals(listOf(listOf("a")), owner.state.value.history.back.map { it.query.includeTags })
    }

    @Test fun `closing on a waiting edit runs it at once and settles it`() = runTest(mainDispatcherRule.dispatcher) {
        val owner = searched(ViewModelSearchAdapter(), "a")
        owner.onAction(SearchAction.AddIncludeTerm(SearchTerm("b")))
        owner.onAction(SearchAction.SettleSearch)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), owner.state.value.query.applied.includeTags)
        assertEquals(listOf("a+b", "a"), recordedSearches())
    }

    @Test fun `opening a post or scrolling the results settles a live search`() = runTest(mainDispatcherRule.dispatcher) {
        val owner = searched(ViewModelSearchAdapter(), "a")
        liveEdit(owner, "b")
        val posts = owner.state.value.content.results
        owner.onAction(SearchAction.OpenResult(posts.first().id, posts, scrollOffsetHint = 0))
        advanceUntilIdle()
        assertEquals(listOf("a+b", "a"), recordedSearches())

        liveEdit(owner, "c")
        acknowledgeScrollRequest(owner)
        owner.onAction(SearchAction.ScrollChanged(0, 0))
        advanceUntilIdle()
        assertEquals(2, recordedSearches().size)
        owner.onAction(SearchAction.ScrollChanged(2, 10))
        advanceUntilIdle()
        assertEquals(listOf("a+b+c", "a+b", "a"), recordedSearches())
    }

    @Test fun `stepping back and forward returns to each settled search's place`() = runTest(mainDispatcherRule.dispatcher) {
        val owner = searched(ViewModelSearchAdapter(), "a")
        acknowledgeScrollRequest(owner)
        owner.onAction(SearchAction.ScrollChanged(3, 30))
        liveEdit(owner, "b")
        owner.onAction(SearchAction.SettleSearch)
        advanceUntilIdle()
        acknowledgeScrollRequest(owner)
        owner.onAction(SearchAction.ScrollChanged(5, 50))

        owner.onAction(SearchAction.UndoSearch)
        advanceUntilIdle()
        assertEquals(listOf("a"), owner.state.value.query.applied.includeTags)
        assertEquals(SearchScrollState(3, 30), openScrollTarget(owner))
        assertTrue(owner.state.value.canRedoSearch)
        acknowledgeScrollRequest(owner)

        owner.onAction(SearchAction.RedoSearch)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), owner.state.value.query.applied.includeTags)
        assertEquals(SearchScrollState(5, 50), openScrollTarget(owner))
        assertFalse(owner.state.value.canRedoSearch)
    }

    @Test fun `scrolling a search stepped back to does not record it again`() = runTest(mainDispatcherRule.dispatcher) {
        val owner = searched(ViewModelSearchAdapter(), "a")
        liveEdit(owner, "b")
        owner.onAction(SearchAction.SettleSearch)
        advanceUntilIdle()
        owner.onAction(SearchAction.UndoSearch)
        advanceUntilIdle()
        acknowledgeScrollRequest(owner)

        owner.onAction(SearchAction.ScrollChanged(2, 10))
        advanceUntilIdle()
        assertEquals(listOf("a+b", "a"), recordedSearches())
        assertTrue(owner.state.value.canRedoSearch)
    }

    @Test fun `a search settled on partial results is recorded when a newer edit replaces it`() = runTest(mainDispatcherRule.dispatcher) {
        val pixiv = ViewModelSearchAdapter()
        val aibooru = ViewModelSearchAdapter(SourceKey.AIBOORU)
        val owner = viewModel(pixiv, recentsRepository = recents, additionalAdapters = listOf(aibooru))
        restore(owner)
        owner.onAction(SearchAction.ApplyTagSearch(listOf(SearchTermGroup.single(SearchTerm("a")))))
        advanceUntilIdle()
        owner.onAction(SearchAction.RestoreDraftTerms(listOf(SearchTermGroup.single(SearchTerm("slow"))), emptyList()))
        advanceTimeBy(901); runCurrent()
        aibooru.releaseSlowSearch.complete(Unit)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("slow"), owner.state.value.query.applied.includeTags)

        owner.onAction(SearchAction.SettleSearch)
        owner.onAction(SearchAction.RestoreDraftTerms(listOf(SearchTermGroup.single(SearchTerm("b"))), emptyList()))
        pixiv.releaseSlowSearch.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("slow", "a"), recordedSearches())
    }

    @Test fun `applying a draft that live search already runs or shows asks the source only once`() = runTest(mainDispatcherRule.dispatcher) {
        val adapter = ViewModelSearchAdapter()
        val owner = searched(adapter, "base")
        owner.onAction(SearchAction.RestoreDraftTerms(listOf(SearchTermGroup.single(SearchTerm("slow"))), emptyList()))
        advanceTimeBy(601); runCurrent()
        adapter.slowSearchStarted.await()
        val before = adapter.searchCount
        owner.onAction(SearchAction.ApplyDraft)
        adapter.releaseSlowSearch.complete(Unit)
        advanceUntilIdle()
        owner.onAction(SearchAction.ApplyDraft)
        advanceUntilIdle()

        assertEquals(before, adapter.searchCount)
        assertEquals(listOf("slow", "base"), recordedSearches())
    }

    @Test fun `an edit staged over Search runs only once Search is shown again`() = runTest(mainDispatcherRule.dispatcher) {
        val adapter = ViewModelSearchAdapter()
        val owner = searched(adapter, "a")
        val before = adapter.searchCount
        owner.onAction(SearchAction.StageDraftEdit(SearchAction.AddIncludeTerm(SearchTerm("b"))))
        advanceUntilIdle()
        assertEquals(before, adapter.searchCount)
        assertEquals(listOf("a", "b"), owner.state.value.query.draft.includeTags)

        owner.onAction(SearchAction.Resume)
        advanceTimeBy(601); runCurrent()
        advanceUntilIdle()
        assertEquals(before + 1, adapter.searchCount)
        assertEquals(listOf("a", "b"), owner.state.value.query.applied.includeTags)
    }

    @Test fun `an edit staged before Search first opens runs on the restored draft at its first resume`() = runTest(mainDispatcherRule.dispatcher) {
        val adapter = ViewModelSearchAdapter()
        val stored = InMemoryQueryRepository()
        val slowQueries = object : QueryRepository by stored {
            override fun observeAppliedQuery(modeKey: String) = stored.observeAppliedQuery(modeKey).onStart { delay(1_000) }
        }
        val owner = viewModel(adapter, queryRepository = slowQueries, recentsRepository = recents)
        owner.onAction(SearchAction.StageDraftEdit(SearchAction.AddIncludeTerm(SearchTerm("b"))))
        owner.onAction(SearchAction.Resume)
        owner.onAction(SearchAction.Restore)
        advanceUntilIdle()
        assertEquals(1, adapter.searchCount)
        assertEquals(listOf("b"), owner.state.value.query.applied.includeTags)
    }

    @Test fun `switching back to the empty draft replaces a first search cancelled before it answered`() =
        runTest(mainDispatcherRule.dispatcher) { assertFirstSearchReplacedAfterSwitching(QueryMode.Unified) }

    @Test fun `a second switch keeps the replacement for a cancelled first search scheduled`() =
        runTest(mainDispatcherRule.dispatcher) {
            assertFirstSearchReplacedAfterSwitching(QueryMode.Source(SourceKey.AIBOORU), QueryMode.Unified)
        }

    private suspend fun TestScope.assertFirstSearchReplacedAfterSwitching(vararg modes: QueryMode) {
        val owner = viewModel(ViewModelSearchAdapter(), additionalAdapters = listOf(ViewModelSearchAdapter(SourceKey.AIBOORU)), recentsRepository = recents)
        restore(owner)
        owner.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
        owner.onAction(SearchAction.ApplyDraft)
        modes.forEach { owner.onAction(SearchAction.SelectMode(it)) }
        assertFalse(owner.state.value.hasPendingChanges)
        assertTrue(owner.state.value.execution.liveSearchWaiting)
        advanceUntilIdle()
        assertFalse(owner.state.value.execution.liveSearchWaiting)
        assertTrue(owner.state.value.content.hasExecutedSearch)
        assertEquals(modes.last(), owner.state.value.query.applied.mode)
    }

    private suspend fun TestScope.searched(adapter: ViewModelSearchAdapter, tag: String): SearchViewModel {
        val owner = viewModel(adapter, recentsRepository = recents)
        restore(owner)
        owner.onAction(SearchAction.ApplyTagSearch(listOf(SearchTermGroup.single(SearchTerm(tag))), mode = QueryMode.Source(SourceKey.PIXIV)))
        advanceUntilIdle()
        return owner
    }

    private fun TestScope.liveEdit(owner: SearchViewModel, tag: String) {
        owner.onAction(SearchAction.AddIncludeTerm(SearchTerm(tag)))
        advanceUntilIdle()
    }

    private suspend fun recordedSearches(): List<String> =
        recents.observeSearches().first().map { it.query.includeTags.joinToString("+") }

    private fun acknowledgeScrollRequest(owner: SearchViewModel) {
        val restored = owner.state.value.restoration as SearchRestorationUiState.Restored
        owner.onAction(SearchAction.ScrollRestorationApplied(restored.scrollRequestId))
    }

    private fun openScrollTarget(owner: SearchViewModel): SearchScrollState? =
        (owner.state.value.restoration as SearchRestorationUiState.Restored).scrollState
}
