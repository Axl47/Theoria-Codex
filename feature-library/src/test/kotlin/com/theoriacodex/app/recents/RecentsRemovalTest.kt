package com.theoriacodex.app.recents

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecentsRemovalTest {
    @get:Rule val compose = createComposeRule()

    private val post = RecentPostEntry(testPost(title = "Watched post"), 100L, ViewerStreamSource.SEARCH, null)
    private val search = RecentSearchEntry(
        query = Query(QueryMode.Source(SourceKey.PIXIV), listOf("night"), emptyList(), SortMode.NEWEST, null, null),
        queryHash = "night",
        searchedAtEpochMs = 100L,
    )
    private val removedPosts = mutableListOf<RecentPostEntry>()
    private val removedSearches = mutableListOf<RecentSearchEntry>()

    @Test
    fun `TalkBack reaches Remove from History on the focused search row and post tile`() {
        compose.setContent { MaterialTheme { TestRecents() } }

        openSection("Watched")
        removeFromHistory(compose.onNodeWithTag(searchCardTestTag(post.post.id)))
        openSection("Searches")
        removeFromHistory(compose.onNodeWithTag("Recent search:${search.queryHash}"))

        assertEquals(listOf(post), removedPosts)
        assertEquals(listOf(search), removedSearches)
    }

    /** Runs the custom action on the merged node, the one TalkBack focuses, failing when it is missing there. */
    private fun removeFromHistory(node: SemanticsNodeInteraction) {
        val actions = node.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val remove = actions.single { it.label == "Remove from History" }
        compose.runOnIdle { remove.action() }
    }

    private fun openSection(label: String) {
        compose.onNodeWithContentDescription("Filter History by section").performClick()
        compose.onNodeWithText(label).performClick()
    }

    @Composable
    private fun TestRecents(removedSearch: RecentSearchEntry? = null) {
        RecentsScreen(
            watchedPosts = listOf(post), codexPosts = emptyList(), searches = listOf(search), fypSearches = emptyList(),
            activity = listOf(RecentActivityEntry.Watched(post), RecentActivityEntry.Search(search)),
            onRemoveSearch = { removedSearches += it }, onRemovePost = { removedPosts += it }, removedSearch = removedSearch,
            onRequestSaveToCodex = {}, onSaveToDevice = {}, onGoToSearch = {},
            onOpenWatchedPost = { _, _ -> }, onOpenCodexPost = { _, _ -> }, onOpenSearch = {}, onOpenFypSearch = {}, onClear = {},
        )
    }
}
