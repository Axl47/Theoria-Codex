package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.UnsupportedRelatedPostsLoader
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.Post
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.NewPostMarks
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveCodexRefreshTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `pulling a live collection refreshes its feed, shows Checking until the refresh ends, then shows the newest post (ADR 0225)`() {
        val owner = FakeOwner()
        var posts by mutableStateOf((1..12).map { testPost(sourcePostId = "p$it") })
        compose.setContent { MaterialTheme { LiveCollection(owner, posts) } }

        compose.onRoot().performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        compose.runOnIdle { assertEquals(1, owner.refreshes) }
        compose.onNodeWithText("Checking").assertExists()

        val fresh = (1..6).map { testPost(sourcePostId = "fresh$it") }
        posts = fresh + posts
        owner.state.value = LiveCodexFeedState(refreshes = 1)
        compose.waitForIdle()
        compose.onNodeWithText("Checking").assertDoesNotExist()
        compose.onNodeWithTag(searchCardTestTag(fresh.first().id)).assertIsDisplayed()
    }

    @Test fun `an empty live collection still takes a pull (ADR 0225)`() {
        val owner = FakeOwner()
        compose.setContent { MaterialTheme { LiveCollection(owner, emptyList()) } }

        compose.onRoot().performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        compose.runOnIdle { assertEquals(1, owner.refreshes) }
    }

    @Test fun `pulling the Saved shelves refreshes every live feed on them (ADR 0225)`() {
        val owners = listOf(FakeOwner(), FakeOwner())
        compose.setContent {
            MaterialTheme {
                LiveCodexRefreshBox(owners, Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize()) { items(30) { Text("Shelf $it") } }
                }
            }
        }

        compose.onRoot().performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        compose.runOnIdle { assertEquals(listOf(1, 1), owners.map(FakeOwner::refreshes)) }
    }

    @Test fun `a shelf that refreshed while off screen shows its newest posts when it comes back (ADR 0225)`() {
        val owner = FakeOwner()
        var onScreen by mutableStateOf(true)
        var scrolls = 0
        compose.setContent {
            val shelves = rememberSaveableStateHolder()
            if (onScreen) shelves.SaveableStateProvider("followed") { ShowNewestAfterRefresh(owner) { scrolls++ } }
        }

        compose.runOnIdle { onScreen = false }
        compose.runOnIdle { owner.state.value = LiveCodexFeedState(refreshes = 1) }
        compose.runOnIdle { onScreen = true }
        compose.runOnIdle { assertEquals(1, scrolls) }
    }

    @Test fun `an open collection reloads for new posts once per visit, even after a trip to the Viewer, while a shelf follows every check (ADR 0152)`() {
        val collection = FakeOwner().apply { state.value = LiveCodexFeedState(loadedAtEpochMs = 10) }
        val shelf = FakeOwner().apply { state.value = LiveCodexFeedState(loadedAtEpochMs = 10) }
        var marks by mutableStateOf(listOf(NewPostMarks(latestPostIds = listOf("PIXIV:a"), checkedAtEpochMs = 5)))
        var visiting by mutableStateOf(true)
        compose.setContent {
            val entries = rememberSaveableStateHolder()
            if (visiting) entries.SaveableStateProvider("collection") { rememberNewPostsReload(collection, collection.state.collectAsState().value, marks) }
            rememberNewPostsReload(shelf, shelf.state.collectAsState().value, marks, followNewMarks = true)
        }

        compose.runOnIdle { visiting = false }
        compose.runOnIdle { marks = listOf(NewPostMarks(latestPostIds = listOf("PIXIV:a"), checkedAtEpochMs = 20)) }
        compose.runOnIdle { visiting = true }
        compose.runOnIdle {
            assertEquals(0, collection.refreshes)
            assertEquals(1, shelf.refreshes)
        }
    }

    @Composable
    private fun LiveCollection(owner: LiveCodexFeedOwner, posts: List<Post>) = CodexDetailScreen(
        codexId = FOLLOWED_CODEX_ID, codexName = "Followed", posts = posts, sortMode = CodexSortMode.NEWEST_SAVED,
        availableSources = emptySet(), onOpenViewer = { _, _ -> }, onAddPostsToAnotherCodex = {}, onRemovePosts = {},
        onSavePostToDevice = {}, onBack = {}, customHeader = { _, _ -> Text("Followed") }, liveOwner = owner,
    )

    private class FakeOwner : LiveCodexFeedOwner {
        var refreshes = 0
        override val state = MutableStateFlow(LiveCodexFeedState())
        override val relatedState = MutableStateFlow<RelatedPostsUiState>(RelatedPostsUiState.Idle)
        override val related = RelatedPostsRouteController(MainScope(), UnsupportedRelatedPostsLoader, { relatedState.value }, { emptyList() },
            updateState = { relatedState.value = it })
        override fun refresh() {
            refreshes++
            state.value = LiveCodexFeedState(loading = true, refreshing = true)
        }
        override fun loadMore() = Unit
        override fun retry() = Unit
    }
}
