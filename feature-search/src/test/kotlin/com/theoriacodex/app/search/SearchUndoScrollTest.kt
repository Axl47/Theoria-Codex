package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.related.testRelatedShelf
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Search's real screen and owner together, driven through the composer like the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchUndoScrollTest {
    @get:Rule val compose = createComposeRule()

    private val saved = SavedStateHandle()
    private val owner = SearchViewModel(
        coordinator = testSearchCoordinator(ViewModelSearchRegistry(PagedAdapter()), computationDispatcher = Dispatchers.Main),
        savedStateHandle = saved,
        scrollPersistenceDelayMs = 0L,
    )

    // The place lies more pages below the first than page demand drains on its own between scrolls.
    @Test fun `Undo loads every page up to a deep saved place and returns to it`() {
        compose.setContent {
            val state by owner.state.collectAsState()
            MaterialTheme {
                SearchScreen(
                    state = state, related = testRelatedShelf(),
                    onAction = owner::onAction, resolvePostById = { null }, recoverPostMedia = { _, _ -> null },
                    onRequestSaveToCodex = {},
                )
            }
        }
        compose.runOnIdle { owner.onAction(SearchAction.Restore) }
        settle { it.restoration is SearchRestorationUiState.Restored }
        search("first")
        val grid = compose.onNode(
            hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true,
        )
        while ((saved.savedSearchScrollState()?.firstVisibleItemIndex ?: 0) < DEEP_ANCHOR) {
            grid.performTouchInput { swipeUp() }
            settle { true }
        }
        val left = checkNotNull(saved.savedSearchScrollState())

        compose.onNodeWithContentDescription("Expand Search").performClick()
        search("second")
        compose.onNodeWithContentDescription("Close search editor").performClick()
        settle { true }
        compose.onNodeWithTag(searchCardTestTag(PostId(SourceKey.PIXIV, "second-0"))).assertIsDisplayed()

        compose.onNode(hasContentDescription("Expand Search") or hasTestTag("Search bar")).performClick()
        compose.onNodeWithContentDescription("More search actions").performClick()
        compose.onNodeWithText("Undo last search").performClick()
        settle { it.query.applied.includeTags == listOf("first") }
        compose.onNodeWithContentDescription("Close search editor").performClick()
        settle { true }

        assertEquals(left, saved.savedSearchScrollState())
        compose.onNodeWithTag(searchCardTestTag(PostId(SourceKey.PIXIV, "first-${left.firstVisibleItemIndex}"))).assertIsDisplayed()
    }

    private fun search(tag: String) {
        compose.runOnIdle {
            owner.onAction(SearchAction.ApplyTagSearch(listOf(SearchTermGroup.single(SearchTerm(tag))), mode = QueryMode.Source(SourceKey.PIXIV)))
        }
        settle { it.query.applied.includeTags == listOf(tag) }
    }

    /** Waits until no search runs, no scroll request is open, and [done] holds. */
    private fun settle(done: (SearchUiState) -> Boolean) {
        compose.waitUntilSettled(5_000) {
            // Main-thread delays (the request timeout among them) run only as Robolectric's clock moves.
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
            val state = owner.state.value
            val restored = state.restoration as? SearchRestorationUiState.Restored
            state.execution.activeRequestId == null && restored?.scrollState == null && done(state)
        }
        compose.waitForIdle()
    }

    /** Ten pages of twenty posts per tag, of varied heights. */
    private class PagedAdapter : SourceAdapter {
        override val sourceKey = SourceKey.PIXIV
        override val capabilities = SourceCapabilities(
            supportsSortNewest = true, supportsSortPopular = true, supportsSortTop = true,
            supportsSortRandom = true, supportsExcludeTagsServerSide = true,
        )

        override suspend fun search(query: Query, pageToken: String?): Page<Post> {
            val tag = query.includeTags.first()
            val page = pageToken?.toInt() ?: 0
            val posts = (page * PAGE_SIZE until (page + 1) * PAGE_SIZE).map {
                testPost(sourcePostId = "$tag-$it", width = 100, height = 60 + (it * 37) % 120)
            }
            return Page(posts, (page + 1).takeIf { it < PAGES }?.toString())
        }

        override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun resolvePost(id: PostId): Post? = null
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val PAGES = 10
        // Past the first page plus the three that page demand loads unasked.
        const val DEEP_ANCHOR = 85
    }
}
