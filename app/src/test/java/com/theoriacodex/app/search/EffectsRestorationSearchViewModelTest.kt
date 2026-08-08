package com.theoriacodex.app.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.repository.QueryRepository
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.testing.TestAnimatedDurationEnricher
import com.theoriacodex.app.testing.animatedTestPost
import com.theoriacodex.app.media.AnimatedDurationEnricher
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
internal class EffectsRestorationSearchViewModelTest : SearchViewModelTestFixture() {
    @Test
    fun `open result emits buffered typed navigation effect`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(ViewModelSearchAdapter())
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("fast")))
            viewModel.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()
            val post = viewModel.state.value.content.results.single()

            viewModel.onAction(
                SearchAction.OpenResult(
                    postId = post.id,
                    visibleResults = listOf(post),
                    scrollOffsetHint = 27,
                ),
            )
            runCurrent()

            val effect = viewModel.effects.first() as SearchEffect.OpenViewer
            assertEquals(listOf(post), effect.posts)
            assertEquals(0, effect.context.startIndex)
            assertEquals(27, effect.context.scrollOffsetHint)
        }

    @Test
    fun `direct nhentai id emits a static viewer effect from owner work`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(ViewModelSearchAdapter(SourceKey.NHENTAI))
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.NHENTAI)))
            viewModel.onAction(SearchAction.CommitTagInput("123"))
            advanceUntilIdle()

            val effect = viewModel.effects.first() as SearchEffect.OpenViewer
            assertEquals("123", effect.posts.single().id.sourcePostId)
            assertEquals("nhentai-search-id:123", effect.context.queryHash)
            assertFalse(effect.liveSearchBinding)
        }

    @Test
    fun `saved state restores compact draft and scroll reconstruction input`() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val first = viewModel(ViewModelSearchAdapter(), savedState = savedState)
            restore(first)
            val range = DateRange(fromEpochMs = 100L, toEpochMs = 200L)
            first.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            first.onAction(SearchAction.AddIncludeTerm(SearchTerm("restored tag")))
            first.onAction(SearchAction.SelectSort(SortMode.POPULAR))
            first.onAction(SearchAction.SetDateRange(range))
            first.onAction(SearchAction.SetMinimumScore(42))
            first.onAction(SearchAction.ScrollChanged(4, 19))
            runCurrent()

            assertNotNull(savedState.get<String>(SearchSavedStateKeys.DRAFT_QUERY))
            assertEquals(true, savedState.get<Boolean>(SearchSavedStateKeys.RESTORATION_COMPLETED))

            val recreated = viewModel(ViewModelSearchAdapter(), savedState = savedState)
            recreated.onAction(SearchAction.Restore)
            advanceUntilIdle()

            val restored = recreated.state.value
            assertEquals(QueryMode.Source(SourceKey.PIXIV), restored.query.draft.mode)
            assertEquals(listOf("restored tag"), restored.query.draft.includeTags)
            assertEquals(SortMode.POPULAR, restored.query.draft.sort)
            assertEquals(range, restored.query.draft.dateRange)
            assertEquals(42, restored.query.draft.minScore)
            assertEquals(
                SearchRestorationUiState.Restored(
                    restoredQuery = true,
                    scrollState = com.theoriacodex.data.repository.SearchScrollState(4, 19),
                    scrollRequestId = 1L,
                ),
                restored.restoration,
            )
        }

}
