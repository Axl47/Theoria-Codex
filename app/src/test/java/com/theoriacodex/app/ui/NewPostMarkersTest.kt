package com.theoriacodex.app.ui

import android.app.Application
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.codex.FOLLOWED_CODEX_ID
import com.theoriacodex.app.codex.SAVED_SEARCHES_CODEX_ID
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.app.ui.components.FeedDurations
import com.theoriacodex.app.ui.components.FeedGrid
import com.theoriacodex.app.ui.components.SeenPostsEffect
import com.theoriacodex.app.ui.components.rememberFeedView
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.data.repository.NewPostMarks
import com.theoriacodex.data.repository.SavedSearchNewPosts
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.markKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NewPostMarkersTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val graph by lazy {
        runBlocking(Dispatchers.IO) {
            JourneyAppContainer(ApplicationProvider.getApplicationContext(), temporary.newFolder(), emptyMap(), scope).also { it.awaitReady() }
        }
    }
    private val posts = listOf("a", "b", "c", "d", "e", "f").map { testPost(sourcePostId = it) }
    private val show = mutableStateOf(true)

    @After fun cleanup() {
        compose.runOnIdle { show.value = false }
        compose.waitForIdle()
        runBlocking(Dispatchers.IO) { graph.shutdown() }
        scope.cancel()
    }

    @Test
    fun `a visit clears only the new posts it showed mostly on screen, keeps every marker until it ends, and the Viewer clears what it opens (ADR 0019, 0152)`() {
        val data = graph.data
        runBlocking {
            data.uiRestoreRepository.updateSavedSearchNewPosts {
                SavedSearchNewPosts(marks = mapOf("search" to NewPostMarks(latestPostIds = posts.map { it.id.markKey() }, checkedAtEpochMs = 1)))
            }
        }
        lateinit var navigation: NavHostController
        compose.setContent {
            navigation = rememberNavController()
            if (show.value) NavHost(navigation, startDestination = "library") {
                composable("library") { Text("library") }
                composable("collection") { Collection(data) }
                composable("viewer") { Text("viewer") }
            }
        }
        compose.runOnIdle { navigation.navigate("collection") }
        awaitText("new: a, b, c, d, e, f")
        awaitLiveNewPosts(data, "c", "d", "e", "f")

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        awaitLiveNewPosts(data, "e", "f")
        compose.onNodeWithText("new: a, b, c, d, e, f").assertExists()

        compose.runOnIdle { navigation.navigate("viewer") }
        runBlocking {
            data.markViewedNewPost(posts[5], ViewerSession(posts, launchedFrom(FOLLOWED_CODEX_ID)))
            data.markViewedNewPost(posts[4], ViewerSession(posts, launchedFrom(SAVED_SEARCHES_CODEX_ID)))
        }
        compose.runOnIdle { navigation.popBackStack() }
        awaitText("new: a, b, c, d, f")

        compose.runOnIdle {
            navigation.popBackStack()
            navigation.navigate("collection")
        }
        awaitText("new: f")
    }

    @Composable
    private fun Collection(data: DataDependencies) {
        val news = remember { data.uiRestoreRepository.observeSavedSearchNewPosts() }.collectAsState(null).value ?: return
        val markers = rememberNewPostMarkers(data, SAVED_SEARCHES_CODEX_ID, posts) {
            news.marks.values.flatMapTo(mutableSetOf()) { it.newPostIds }
        }
        val feed = rememberFeedView("new-post-markers", posts, SearchVisibilityFilters(), FeedDurations.NONE, RelatedPostsUiState.Idle)
        val grid = rememberLazyStaggeredGridState()
        SeenPostsEffect(grid, feed.visiblePosts, markers, requireNotNull(newPostsSeenHandler(data, SAVED_SEARCHES_CODEX_ID)))
        Column {
            Text("new: " + posts.filter { it.id in markers }.joinToString { it.id.sourcePostId }.ifEmpty { "none" })
            FeedGrid(feed, grid, footerMessage = null, modifier = Modifier.width(400.dp).height(500.dp), shelf = {}) { _, _ ->
                Box(Modifier.height(400.dp))
            }
        }
    }

    private fun awaitLiveNewPosts(data: DataDependencies, vararg ids: String) {
        val expected = ids.mapTo(mutableSetOf()) { id -> posts.single { it.id.sourcePostId == id }.id.markKey() }
        compose.waitUntilSettled(10_000) { runBlocking { liveNewPosts(data).toSet() == expected } }
    }

    private fun launchedFrom(codexId: String) = ViewerLaunchContext("codex:$codexId", 0, ViewerStreamSource.CODEX, 0)

    private suspend fun liveNewPosts(data: DataDependencies) =
        data.uiRestoreRepository.observeSavedSearchNewPosts().first().marks.values.flatMap { it.newPostIds }

    private fun awaitText(text: String) =
        compose.waitUntilSettled(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
}
