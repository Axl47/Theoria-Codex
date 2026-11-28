package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.UnsupportedRelatedPostsLoader
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexSortMode
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

    @Test fun `pulling a live collection refreshes its feed and shows Checking until the refresh ends (ADR 0225)`() {
        val owner = FakeOwner()
        val posts = (1..12).map { testPost(sourcePostId = "p$it") }
        compose.setContent {
            MaterialTheme {
                CodexDetailScreen(
                    codexId = FOLLOWED_CODEX_ID, codexName = "Followed", posts = posts, sortMode = CodexSortMode.NEWEST_SAVED,
                    availableSources = emptySet(), onOpenViewer = { _, _ -> }, onAddPostsToAnotherCodex = {}, onRemovePosts = {},
                    onSavePostToDevice = {}, onBack = {}, customHeader = { _, _ -> Text("Followed") }, liveOwner = owner,
                )
            }
        }

        compose.onRoot().performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        compose.runOnIdle { assertEquals(1, owner.refreshes) }
        compose.onNodeWithText("Checking").assertExists()

        owner.state.value = LiveCodexFeedState()
        compose.waitForIdle()
        compose.onNodeWithText("Checking").assertDoesNotExist()
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
