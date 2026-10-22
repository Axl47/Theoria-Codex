package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.appshell.AppShellViewModel
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.TheoriaAppContent
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SavedSearchesCodexJourneyTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `Library opens merged saved searches and exclusion survives Viewer return and route reentry`() {
        val source = SourceKey.PIXIV
        val posts = listOf("first", "second", "shared").map { id ->
            val media = ImageRef(null, "/nonexistent/saved-$id.jpg", "image/jpeg")
            testPost(source, id).copy(preview = media, full = media, media = listOf(media), width = 160, height = 120,
                canonicalTags = if (id == "shared") listOf("a", "b") else listOf(if (id == "first") "a" else "b"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val show = mutableStateOf(true)
        var owners: ViewModelStoreOwner? = null
        val graph = runBlocking(Dispatchers.IO) {
            JourneyAppContainer(ApplicationProvider.getApplicationContext(), temporary.newFolder(), mapOf(source to posts), scope).also {
                it.awaitReady()
                listOf("a" to "First search", "b" to "Second search").forEach { (tag, name) ->
                    it.data.savedSearches.save(name, RecentSearchEntry(
                        Query(QueryMode.Source(source), listOf(tag), emptyList(), SortMode.NEWEST, null, null), tag, 1,
                    ))
                }
            }
        }
        try {
            compose.setContent {
                val owner = LocalViewModelStoreOwner.current
                SideEffect { owners = owner }
                if (show.value) TheoriaAppContent(graph, viewModel<ViewerSessionRetentionViewModel>(), viewModel<AppShellViewModel>())
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Codex").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Codex").performClick()
            compose.onNodeWithText("Codex Library").assertIsDisplayed()
            compose.onNodeWithText("Searches").assertDoesNotExist()
            compose.onNodeWithText("Likes: 0, Followed: 0, Search: 2").assertIsDisplayed()
            compose.onNodeWithText("Saved").performClick()
            compose.onNodeWithText("Likes").performTouchInput { longClick() }
            compose.onNodeWithContentDescription("Clear likes").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Followed").assertIsDisplayed()
            compose.onNodeWithText("Searches").performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithContentDescription("Include First search").assertExists()
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("Searches").performClick()
            val first = searchCardTestTag(posts[0].id)
            val second = searchCardTestTag(posts[1].id)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(first).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(second).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, compose.onAllNodesWithTag(searchCardTestTag(posts[2].id)).fetchSemanticsNodes().size)
            compose.onNode(hasText("Searches") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
                .performTouchInput { longClick() }
            compose.onNodeWithContentDescription("Include First search").performClick()
            compose.onNodeWithText("Done").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(first).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag(second).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { compose.onNodeWithContentDescription("Back").isDisplayed() }
            val roots = graph.registry.requests.count { it.pageToken == null }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag(second).assertIsDisplayed()
            assertEquals(roots, graph.registry.requests.count { it.pageToken == null })
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Searches").performScrollTo()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(second).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(first).assertDoesNotExist()
            assertEquals(roots, graph.registry.requests.count { it.pageToken == null })
            // Leaving Saved entirely must retain the same feed, too.
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Saved").performClick()
            compose.onNodeWithText("Searches").performScrollTo()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(second).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(roots, graph.registry.requests.count { it.pageToken == null })
            compose.onNodeWithTag(second).performClick()
            compose.waitUntil(10_000) { compose.onNodeWithContentDescription("Back").isDisplayed() }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Searches").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(second).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Searches") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
                .performTouchInput { longClick() }
            compose.onNodeWithContentDescription("Include First search").assertIsOff()
            compose.onNodeWithText("Exclude all").performClick()
            compose.onNodeWithText("Done").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(second).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Save a search from Recents or include a search in this feed").assertIsDisplayed()
        } finally {
            compose.runOnIdle { show.value = false; owners?.viewModelStore?.clear() }
            runBlocking(Dispatchers.IO) { graph.shutdown() }
            scope.cancel()
        }
    }
}
