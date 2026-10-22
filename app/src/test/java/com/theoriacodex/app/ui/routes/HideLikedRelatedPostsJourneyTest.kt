package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.appshell.AppShellViewModel
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.codex.likesCodexIdForProfile
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.TheoriaAppContent
import com.theoriacodex.app.ui.components.SEARCH_FEED_FAB_CONTEXT
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise actual Room Likes/Codex writes and route callbacks with both membership filters enabled. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HideLikedRelatedPostsJourneyTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `grid likes retain original and recommended posts until dismissal`() = verifyLikes(fullscreen = false)
    @Test fun `fullscreen likes retain original and recommended posts until dismissal`() = verifyLikes(fullscreen = true)

    private fun verifyLikes(fullscreen: Boolean) {
        val posts = (0..7).map { index ->
            val media = ImageRef(null, "/nonexistent/like-$index.jpg", "image/jpeg")
            testPost(SourceKey.PIXIV, "like-$index").copy(
                preview = media, full = media, media = listOf(media), width = 160, height = 120,
                canonicalTags = listOf(if (index >= 6) "seed" else "related"),
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val showApp = mutableStateOf(true)
        var viewModelOwner: ViewModelStoreOwner? = null
        val graph = runBlocking(Dispatchers.IO) {
            JourneyAppContainer(ApplicationProvider.getApplicationContext(), temporary.newFolder(),
                mapOf(SourceKey.PIXIV to posts), scope).also {
                it.awaitReady()
                it.data.uiRestoreRepository.setFeedFabRestoreState(
                    SEARCH_FEED_FAB_CONTEXT, FeedFabRestoreState(hideLiked = true, hideSaved = true),
                )
                it.data.recentsRepository.recordSearch(
                    Query(QueryMode.Source(SourceKey.PIXIV), listOf("seed"), emptyList(), SortMode.NEWEST, null, null),
                    "likes-query", RecentSearchKind.SOURCE, listOf(SourceKey.PIXIV), emptyMap(),
                )
            }
        }
        try {
            compose.setContent {
                val owner = LocalViewModelStoreOwner.current
                SideEffect { viewModelOwner = owner }
                if (showApp.value) TheoriaAppContent(graph, viewModel<ViewerSessionRetentionViewModel>(), viewModel<AppShellViewModel>())
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Recents").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Recents").performClick()
            compose.onNodeWithText("Searches").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("Recent search:likes-query").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("Recent search:likes-query").performClick()
            val verticalGrid = hasScrollToIndexAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            val seed = posts[6]
            val recommendation = posts[0]
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(searchCardTestTag(seed.id)).fetchSemanticsNodes().isNotEmpty() }
            for (post in listOf(seed, recommendation)) {
                val tag = searchCardTestTag(post.id)
                compose.onNode(verticalGrid).performScrollToNode(hasTestTag(tag))
                if (fullscreen) {
                    compose.onNodeWithTag(tag).performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithContentDescription("Like post").performClick()
                } else {
                    compose.onNode(hasContentDescription("Like post") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                        .performClick()
                }
                // A real like also adds this post to the system Likes Codex, activating Hide saved.
                compose.waitUntil(10_000) {
                    compose.waitForIdle()
                    runBlocking(Dispatchers.IO) {
                        post.id in graph.content.observeSavedPostIds(setOf(likesCodexIdForProfile("profile-main"))).first()
                    }
                }
                if (fullscreen) {
                    compose.onNodeWithContentDescription("Unlike post").assertIsDisplayed()
                    compose.onNodeWithContentDescription("Back").performClick()
                }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("More like this").fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(verticalGrid).performScrollToIndex(0)
                compose.onNodeWithTag(searchCardTestTag(seed.id)).assertIsDisplayed()
                compose.onNodeWithTag(tag).assertIsDisplayed()
            }
            compose.onNode(verticalGrid).performScrollToNode(hasText("Dismiss"))
            compose.onNodeWithText("Dismiss").performClick()
            compose.onNode(verticalGrid).performScrollToIndex(0)
            compose.onNodeWithTag(searchCardTestTag(seed.id)).assertDoesNotExist()
            compose.onNodeWithTag(searchCardTestTag(recommendation.id)).assertDoesNotExist()
            compose.onNodeWithTag(searchCardTestTag(posts[7].id)).assertIsDisplayed()
        } catch (error: Throwable) {
            println(compose.onRoot().printToString())
            println(runBlocking(Dispatchers.IO) {
                "Profile=${graph.settings.observeSettings().first().activeProfileId}; " +
                    "Likes=${graph.content.observeLikedPostIds("profile-main").first()}; " +
                    "Codices=${graph.content.observeCodices().first()}"
            })
            throw error
        } finally {
            compose.runOnIdle {
                showApp.value = false
                viewModelOwner?.viewModelStore?.clear()
            }
            compose.waitForIdle()
            runBlocking(Dispatchers.IO) { graph.shutdown() }
            scope.cancel()
        }
    }
}
