package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.related.testRelatedShelf
import com.theoriacodex.app.search.state.SearchContentUiState
import com.theoriacodex.app.search.state.SearchExecutionUiState
import com.theoriacodex.app.search.state.SearchRequest
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.testing.FEED_POST_CARD
import com.theoriacodex.app.testing.countCompositions
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.components.FeedMarks
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchCardRecompositionTest {
    @get:Rule val compose = createComposeRule()

    private val shown = (0 until 4).map(::post)
    private val state = mutableStateOf(
        SearchUiState(content = SearchContentUiState(results = shown, hasExecutedSearch = true, canLoadMore = true)),
    )

    private val marks = mutableStateOf(FeedMarks.NONE)

    private fun post(index: Int) = testPost(SourceKey.PIXIV, "post-$index").copy(
        preview = ImageRef(null, "/nonexistent/$index.jpg", "image/jpeg"), full = null, media = emptyList(), width = 100, height = 100,
    )

    private fun render() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp, 700.dp)) {
                    SearchScreen(
                        state.value, testRelatedShelf(), {}, resolvePostById = { null }, recoverPostMedia = { _, _ -> null },
                        marks = marks.value, onToggleLike = {}, onRequestSaveToCodex = {},
                    )
                }
            }
        }
        compose.onNodeWithTag(searchCardTestTag(shown.last().id)).assertIsDisplayed()
    }

    @Test fun `a page request and the page it brings recompose only the cards it adds (ADR 0216)`() = countCompositions { counter ->
        render()
        counter.reset()
        compose.runOnIdle {
            state.value = state.value.copy(execution = SearchExecutionUiState(SearchRequest(1, SearchRequestKind.PAGE, state.value.query.applied)))
        }
        compose.waitForIdle()
        assertEquals("cards recomposed by the page request", 0, counter.count(FEED_POST_CARD))

        val page = (4 until 8).map(::post)
        compose.runOnIdle {
            state.value = state.value.copy(
                content = state.value.content.copy(results = shown + page),
                execution = SearchExecutionUiState(),
            )
        }
        compose.waitForIdle()
        val added = page.count { compose.onAllNodesWithTag(searchCardTestTag(it.id)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("cards recomposed by the page", added, counter.count(FEED_POST_CARD))
    }

    @Test fun `a like recomposes only the liked card (ADR 0216)`() = countCompositions { counter ->
        render()
        counter.reset()
        compose.runOnIdle { marks.value = FeedMarks(liked = setOf(shown.first().id)) }
        compose.waitForIdle()
        assertEquals(1, counter.count(FEED_POST_CARD))
    }
}
