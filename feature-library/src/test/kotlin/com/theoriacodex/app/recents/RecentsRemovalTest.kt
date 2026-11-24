package com.theoriacodex.app.recents

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.undo.ProvideUndoSlot
import com.theoriacodex.app.ui.undo.UndoLifetime
import com.theoriacodex.app.ui.undo.UndoOffer
import com.theoriacodex.app.ui.undo.UndoSlot
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
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
    private val undo = UndoSlot()
    private val offers = CoroutineScope(Job())

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

    @Test
    fun `All offers removal on its tiles and keeps a removed search's place for Undo while the source shows it`() {
        val other = search.copy(queryHash = "day", searchedAtEpochMs = 90L)
        offerUndoFor(search)
        compose.setContent { MaterialTheme { ProvideUndoSlot(undo) {
            TestRecents(
                searches = listOf(other),
                activity = listOf(RecentActivityEntry.Watched(post), RecentActivityEntry.Search(other)),
                removedSearch = search,
            )
        } } }

        compose.onNodeWithText("Undo").assertExists()
        removeFromHistory(compose.onNodeWithTag(searchCardTestTag(post.post.id)))
        removeFromHistory(compose.onNodeWithTag("Recent search:${other.queryHash}"))
        assertEquals(listOf(post), removedPosts)
        assertEquals(listOf(other), removedSearches)

        compose.onNodeWithContentDescription("Filter History by source").performClick()
        compose.onNode(hasContentDescription("Gelbooru") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.onNodeWithText("No history").assertExists()
    }

    @Test
    fun `a removed search's place follows the source filter`() {
        offerUndoFor(search)
        compose.setContent { MaterialTheme { ProvideUndoSlot(undo) {
            TestRecents(searches = emptyList(), activity = listOf(RecentActivityEntry.Watched(post)), removedSearch = search)
        } } }
        openSection("Searches")

        compose.onNodeWithText("Undo").assertExists()
        compose.onNodeWithContentDescription("Filter History by source").performClick()
        compose.onNode(hasContentDescription("Gelbooru") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.onNodeWithText("No searches").assertExists()
        compose.onNodeWithContentDescription("Filter History by source").performClick()
        compose.onNodeWithContentDescription("All sources").performClick()
        compose.onNodeWithText("Undo").assertExists()
    }

    @Test
    fun `long-press menus remove a search tile and a post from History`() {
        compose.setContent { MaterialTheme { TestRecents() } }

        compose.onNodeWithTag("Recent search:${search.queryHash}").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithText("Remove from History").performClick()
        compose.onNodeWithTag(searchCardTestTag(post.post.id)).performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithText("Remove from History").performScrollTo().performClick()

        assertEquals(listOf(search), removedSearches)
        assertEquals(listOf(post), removedPosts)
    }

    @After
    fun tearDown() = offers.cancel()

    private fun offerUndoFor(entry: RecentSearchEntry) =
        undo.offer(offers, UndoOffer("Removed", recentSearchRemovalAnchor(entry), lifetime = UndoLifetime.UntilLeft) {})

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
    private fun TestRecents(
        searches: List<RecentSearchEntry> = listOf(search),
        activity: List<RecentActivityEntry> = listOf(RecentActivityEntry.Watched(post), RecentActivityEntry.Search(search)),
        removedSearch: RecentSearchEntry? = null,
    ) {
        RecentsScreen(
            watchedPosts = listOf(post), codexPosts = emptyList(), searches = searches,
            activity = activity,
            onRemoveSearch = { removedSearches += it }, onRemovePost = { removedPosts += it }, removedSearch = removedSearch,
            onGoToSearch = {},
            onOpenWatchedPost = { _, _ -> }, onOpenCodexPost = { _, _ -> }, onOpenSearch = {}, onClear = {},
        )
    }
}
