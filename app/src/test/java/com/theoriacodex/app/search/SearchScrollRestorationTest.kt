package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.buildRelatedFeedProjection
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchContentUiState
import com.theoriacodex.app.search.state.SearchErrorUiState
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchStateChange
import com.theoriacodex.app.search.state.SearchStateReducer
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.SearchScrollState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SearchScrollRestorationTest {
    @get:Rule val compose = createComposeRule()
    private val posts = (0..59).map { testPost(sourcePostId = "restore-$it") }
    private val state = mutableStateOf(SearchUiState(
        content = SearchContentUiState(results = posts.take(20), hasExecutedSearch = true, canLoadMore = true),
        restoration = SearchRestorationUiState.Restored(false, SearchScrollState(22, 17), 1L),
    ))
    private val acknowledgements = mutableListOf<Long>()
    private val writes = mutableListOf<SearchAction.ScrollChanged>()
    private lateinit var grid: LazyStaggeredGridState
    private var hideAnchor = false

    @Test fun `delayed second page restores exact anchor and offset once without layout writes`() {
        render()
        compose.runOnIdle {
            assertTrue(acknowledgements.isEmpty())
            assertTrue(writes.isEmpty())
            state.value = state.value.copy(content = state.value.content.copy(results = posts.take(40)))
        }
        compose.runOnIdle {
            assertEquals(listOf(1L), acknowledgements)
            assertEquals(22, grid.firstVisibleItemIndex)
            assertEquals(17, grid.firstVisibleItemScrollOffset)
            assertEquals(SearchAction.ScrollChanged(22, 17), writes.first())
        }
        // An ordinary page append cannot replay the acknowledged route-entry request.
        compose.onNodeWithTag("restore-grid").performScrollToIndex(25)
        compose.runOnIdle { state.value = state.value.copy(content = state.value.content.copy(results = posts)) }
        compose.runOnIdle {
            assertEquals(25, grid.firstVisibleItemIndex)
            assertEquals(0, grid.firstVisibleItemScrollOffset)
            assertEquals(listOf(1L), acknowledgements)
        }
    }

    @Test fun `failed page retains request and exhausted results permit fallback`() {
        state.value = state.value.copy(content = state.value.content.copy(
            error = SearchErrorUiState("Failed page", SearchRequestKind.PAGE, true), canLoadMore = false,
        ))
        render()
        compose.runOnIdle {
            assertTrue(acknowledgements.isEmpty())
            assertTrue(writes.isEmpty())
            state.value = state.value.copy(content = state.value.content.copy(error = null))
        }
        compose.runOnIdle {
            assertEquals(listOf(1L), acknowledgements)
            assertTrue(grid.firstVisibleItemIndex in 1..19)
        }
    }

    @Test fun `locally hidden loaded anchor uses its visible predecessor without waiting for exhaustion`() {
        hideAnchor = true
        state.value = state.value.copy(content = state.value.content.copy(results = posts.take(40)))
        render()
        compose.runOnIdle {
            assertEquals(listOf(1L), acknowledgements)
            assertEquals(21, grid.firstVisibleItemIndex)
            assertEquals(17, grid.firstVisibleItemScrollOffset)
        }
    }

    @Test fun `deliberate drag supersedes pending restore so later pages do not jump`() {
        render()
        compose.runOnIdle { assertTrue(acknowledgements.isEmpty()) }
        compose.onNodeWithTag("restore-grid").performTouchInput { swipeUp() }
        val userPosition = compose.runOnIdle {
            assertEquals(listOf(1L), acknowledgements)
            grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset
        }
        compose.runOnIdle {
            state.value = state.value.copy(content = state.value.content.copy(results = posts.take(40)))
        }
        compose.runOnIdle {
            assertEquals(userPosition, grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset)
        }
    }

    private fun render() {
        compose.setContent {
            val current = state.value
            val visible = current.content.results.filterNot { hideAnchor && it.id == posts[22].id }
            val projection = buildRelatedFeedProjection(current.content.results, visible, RelatedPostsUiState.Idle)
            grid = rememberLazyStaggeredGridState()
            LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(1), state = grid,
                modifier = Modifier.height(300.dp).testTag("restore-grid")) {
                items(visible.size) { BasicText("Post $it", Modifier.height(100.dp)) }
            }
            val dispatch: (SearchAction) -> Unit = { action ->
                when (action) {
                    is SearchAction.ScrollRestorationApplied -> {
                        acknowledgements += action.requestId
                        state.value = SearchStateReducer.reduce(state.value,
                            SearchStateChange.ScrollRestorationApplied(action.requestId)).state
                    }
                    is SearchAction.ScrollChanged -> writes += action
                    else -> Unit
                }
            }
            SearchScrollRestorationEffect(current, projection, grid, dispatch)
            SearchScrollPersistenceEffect("query", current.restoration, false, projection, grid, dispatch)
        }
    }
}
