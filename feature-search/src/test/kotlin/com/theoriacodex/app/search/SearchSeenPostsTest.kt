package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.theoriacodex.app.related.testRelatedShelf
import com.theoriacodex.app.search.state.SearchContentUiState
import com.theoriacodex.app.search.state.SearchQueryUiState
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.domain.model.PostId
import java.util.concurrent.CopyOnWriteArraySet
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchSeenPostsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `search results report only the new posts they showed (ADR 0019)`() {
        val posts = (0 until 30).map { testPost(sourcePostId = "result-$it") }
        val first = posts.first().id
        val last = posts.last().id
        val seen = CopyOnWriteArraySet<PostId>()
        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    state = SearchUiState(
                        query = SearchQueryUiState(appliedQueryHash = "query"),
                        content = SearchContentUiState(results = posts, hasExecutedSearch = true),
                        restoration = SearchRestorationUiState.Restored(false),
                    ),
                    related = testRelatedShelf(),
                    onAction = {},
                    resolvePostById = { null },
                    recoverPostMedia = { _, _ -> null },
                    onRequestSaveToCodex = {},
                    newPostIds = setOf(first, last),
                    onNewPostsSeen = { seen += it },
                )
            }
        }
        compose.waitUntilSettled(5_000) { first in seen }
        compose.runOnIdle { assertFalse(last in seen) }
    }
}
