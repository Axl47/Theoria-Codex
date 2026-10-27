package com.theoriacodex.app.search

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
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchContentUiState
import com.theoriacodex.app.search.state.SearchQueryUiState
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchStateChange
import com.theoriacodex.app.search.state.SearchStateReducer
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SortMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchApplyScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `Apply resets overlapping results and repeated same-query results after acceptance`() = verifyReset(false)
    @Test fun `Apply resets animated-only results after acceptance`() = verifyReset(true)

    private fun verifyReset(animatedOnly: Boolean) {
        val posts = (0..39).map { index ->
            val media = ImageRef(null, "/nonexistent/apply-$index.${if (animatedOnly) "gif" else "jpg"}",
                if (animatedOnly) "image/gif" else "image/jpeg")
            testPost(sourcePostId = "apply-$index").copy(preview = media, full = media, media = listOf(media))
        }
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
        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    state = state.value,
                    creatorBrowsingSources = emptySet(),
                    fabRestoreState = FeedFabRestoreState().withSearchAnimationFilter(state.value.query.draft.mode) {
                        it.copy(animatedOnly = animatedOnly)
                    },
                    onAction = { action ->
                        when (action) {
                            SearchAction.ApplyDraft -> reduce(SearchStateChange.BeginRequest(
                                ++request, SearchRequestKind.REPLACE, state.value.query.draft,
                            ))
                            is SearchAction.ScrollRestorationApplied -> {
                                acknowledgements += action.requestId
                                reduce(SearchStateChange.ScrollRestorationApplied(action.requestId))
                            }
                            else -> Unit
                        }
                    },
                    resolvePostById = { null },
                    recoverPostMedia = { _, _ -> null },
                    tagVideoCountProvider = { _, _ -> null },
                    fetchTagVideoCounts = { _, _ -> emptyMap() },
                    onOpenCreatorProfile = {},
                    onOpenLegacyCreatorProfile = {},
                    onRequestSaveToCodex = {},
                    onSaveToDevice = {},
                )
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
        compose.onNodeWithText("Apply").assertIsDisplayed()
        grid.performTouchInput { swipeUp() }
        compose.onNodeWithText("Apply").assertDoesNotExist()
        compose.runOnIdle { assertEquals(SortMode.POPULAR, state.value.query.draft.sort) }
        compose.onNode(hasContentDescription("Expand Search") or hasTestTag("Search query input")).performClick()
        compose.onNodeWithText("Apply").assertIsDisplayed()
        repeat(2) {
            grid.performScrollToIndex(20)
            compose.onNode(hasContentDescription("Expand Search") or hasTestTag("Search query input")).performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.runOnIdle {
                reduce(SearchStateChange.ReplaceResults(
                    requestId = request,
                    appliedQuery = state.value.query.applied,
                    appliedQueryHash = "query",
                    results = replacements,
                    statuses = emptyList(),
                    canLoadMore = false,
                ))
                reduce(SearchStateChange.ScrollResetRequested)
            }
            compose.onNodeWithTag(searchCardTestTag(replacements.first().id)).assertIsDisplayed()
            compose.runOnIdle { assertEquals(it + 1, acknowledgements.size) }
        }
        grid.performScrollToIndex(20)
        val card = compose.onNodeWithTag(searchCardTestTag(replacements[20].id))
        card.assertIsDisplayed()
        val top = card.fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle {
            reduce(SearchStateChange.BeginRequest(++request, SearchRequestKind.PAGE, state.value.query.applied))
            reduce(SearchStateChange.AppendPage(request, listOf(testPost(sourcePostId = "next")), emptyList(), false))
        }
        card.assertIsDisplayed()
        assertEquals(top, card.fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { assertEquals(2, acknowledgements.size) }
    }
}
