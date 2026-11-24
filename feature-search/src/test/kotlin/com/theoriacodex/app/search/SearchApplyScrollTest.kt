package com.theoriacodex.app.search

import com.theoriacodex.app.related.testRelatedShelf
import com.theoriacodex.app.feed.FeedCursor
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchContentUiState
import com.theoriacodex.app.search.state.SearchQueryUiState
import com.theoriacodex.app.search.state.SearchRequest
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchStateChange
import com.theoriacodex.app.search.state.SearchStateReducer
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SortMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.theoriacodex.domain.model.MediaFilter

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchApplyScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `running the draft resets overlapping results and repeated same-query results after acceptance`() = verifyReset(false)
    @Test fun `running the draft resets animated-only results after acceptance`() = verifyReset(true)

    @Test fun `animated-only scrolling updates the canonical anchor used on return`() {
        val stills = resetPosts(false)
        val posts = resetPosts(true).flatMapIndexed { index, animated ->
            listOf(animated, stills[index].copy(id = animated.id.copy(sourcePostId = "still-$index")))
        }
        val state = mutableStateOf(SearchUiState(
            query = SearchQueryUiState(appliedQueryHash = "query"),
            content = SearchContentUiState(results = posts, hasExecutedSearch = true),
            restoration = SearchRestorationUiState.Restored(false),
        ))
        var saved = SearchScrollState(0, 0)
        setSearchContent(state, animatedOnly = true) { action ->
            when (action) {
                is SearchAction.ScrollChanged -> saved = SearchScrollState(
                    action.firstVisibleItemIndex, action.firstVisibleItemOffsetPx,
                )
                is SearchAction.ScrollRestorationApplied -> state.value = SearchStateReducer.reduce(
                    state.value, SearchStateChange.ScrollRestorationApplied(action.requestId),
                ).state
                else -> Unit
            }
        }
        val grid = compose.onNode(
            hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true,
        )
        grid.performScrollToIndex(12)
        compose.runOnIdle {
            assertEquals(24, saved.firstVisibleItemIndex)
            state.value = SearchStateReducer.reduce(
                state.value, SearchStateChange.RouteEntryScrollRestorationRequested(saved),
            ).state
        }
        compose.onNodeWithTag(searchCardTestTag(posts[24].id)).assertIsDisplayed()
        compose.onNodeWithTag(searchCardTestTag(posts[0].id)).assertDoesNotExist()
    }

    private fun verifyReset(animatedOnly: Boolean) {
        val posts = resetPosts(animatedOnly)
        val state = mutableStateOf(SearchUiState(
            query = SearchQueryUiState(appliedQueryHash = "query"),
            content = SearchContentUiState(results = posts, hasExecutedSearch = true),
            restoration = SearchRestorationUiState.Restored(false),
        ))
        var request = 0L
        val acknowledgements = mutableListOf<Long>()
        fun reduce(change: SearchStateChange) {
            state.value = SearchStateReducer.reduce(state.value, change).state
        }
        setSearchContent(state, animatedOnly) { action ->
            when (action) {
                SearchAction.ApplyDraft -> reduce(SearchStateChange.BeginRequest(
                    SearchRequest(++request, SearchRequestKind.REPLACE, state.value.query.draft),
                ))
                is SearchAction.ScrollRestorationApplied -> {
                    acknowledgements += action.requestId
                    reduce(SearchStateChange.ScrollRestorationApplied(action.requestId))
                }
                else -> Unit
            }
        }
        val grid = compose.onNode(
            hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true,
        )
        val replacements = posts.reversed()
        compose.runOnIdle {
            state.value = state.value.copy(query = state.value.query.copy(
                draft = state.value.query.draft.copy(sort = SortMode.POPULAR),
            ))
        }
        grid.performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(SortMode.POPULAR, state.value.query.draft.sort) }
        compose.onNode(hasContentDescription("Expand Search")).performClick()
        compose.onNodeWithTag("Search query input").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close search editor").performClick()
        repeat(2) {
            grid.performScrollToIndex(20)
            compose.onNode(hasContentDescription("Expand Search") or hasTestTag("Search bar")).performClick()
            compose.onNodeWithTag("Search query input").performImeAction()
            compose.runOnIdle {
                val current = state.value
                reduce(SearchStateChange.RootResult(request, SearchExecutionResult.Success(
                    executionKey = checkNotNull(current.execution.request).executionKey,
                    query = current.query.applied,
                    sourceScope = current.query.appliedSourceScope,
                    posts = replacements,
                    statuses = emptyList(),
                    continuation = current.lastPage(),
                )))
                reduce(SearchStateChange.ScrollResetRequested)
            }
            compose.onNodeWithTag(searchCardTestTag(replacements.first().id)).assertIsDisplayed()
            compose.runOnIdle { assertEquals(it + 1, acknowledgements.size) }
        }
        verifyPagingAnchor(grid, replacements, state, acknowledgements, { ++request }, ::reduce)
    }

    private fun setSearchContent(
        state: androidx.compose.runtime.MutableState<SearchUiState>,
        animatedOnly: Boolean,
        onAction: (SearchAction) -> Unit,
    ) {
        val media = if (animatedOnly) MediaFilter.ANIMATED else MediaFilter.ANY
        state.value = state.value.copy(query = state.value.query.copy(
            draft = state.value.query.draft.copy(media = media),
            applied = state.value.query.applied.copy(media = media),
        ))
        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    state = state.value,
                    related = testRelatedShelf(),
                    fabRestoreState = FeedFabRestoreState(),
                    onAction = onAction,
                    resolvePostById = { null },
                    recoverPostMedia = { _, _ -> null },
                    onRequestSaveToCodex = {},
                )
            }
        }
    }


    private fun verifyPagingAnchor(
        grid: androidx.compose.ui.test.SemanticsNodeInteraction,
        replacements: List<com.theoriacodex.domain.model.Post>,
        state: androidx.compose.runtime.State<SearchUiState>,
        acknowledgements: List<Long>,
        nextRequest: () -> Long,
        reduce: (SearchStateChange) -> Unit,
    ) {
        grid.performScrollToIndex(20)
        val card = compose.onNodeWithTag(searchCardTestTag(replacements[20].id))
        card.assertIsDisplayed()
        val top = card.fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle {
            val shown = state.value
            val request = SearchRequest(
                nextRequest(), SearchRequestKind.PAGE, shown.query.applied, executionKey = shown.query.appliedQueryHash,
            )
            reduce(SearchStateChange.BeginRequest(request))
            reduce(SearchStateChange.PageResult(request.id, SearchPageResult.Success(
                request.executionKey, listOf(testPost(sourcePostId = "next")), emptyList(), shown.lastPage(),
            )))
        }
        card.assertIsDisplayed()
        assertEquals(top, card.fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { assertEquals(2, acknowledgements.size) }
    }

    private fun SearchUiState.lastPage() = SearchContinuation(
        query.appliedQueryHash, query.appliedSourceScope, emptySet(), emptyList(), FeedCursor(query.applied),
    )

    private fun resetPosts(animatedOnly: Boolean): List<com.theoriacodex.domain.model.Post> {
        return (0..39).map { index ->
            val media = ImageRef(null, "/nonexistent/apply-$index.${if (animatedOnly) "gif" else "jpg"}",
                if (animatedOnly) "image/gif" else "image/jpeg")
            testPost(sourcePostId = "apply-$index").copy(preview = media, full = media, media = listOf(media))
        }
    }

}
