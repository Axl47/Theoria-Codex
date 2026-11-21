package com.theoriacodex.app.recents

import com.theoriacodex.app.search.searchCardTestTag
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SavedSearchEntry
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
class SavedSearchControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the menu's save saves under the query's own name and confirms only once the save commits`() {
        val recent = search(RecentSearchKind.SOURCE)
        var saved by mutableStateOf(emptyList<SavedSearchEntry>())
        var justSaved by mutableStateOf<String?>(null)
        val requested = mutableListOf<Pair<String, RecentSearchEntry>>()
        compose.setContent { MaterialTheme {
            TestRecents(
                searches = listOf(recent),
                saves = RecentSearchSaves(saved, justSaved, { name, entry -> requested += name to entry }, { _, _ -> }),
            )
        } }
        openSearches()
        compose.onNodeWithTag("Recent search:${recent.queryHash}")
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithText("Save search").performClick()
        assertEquals(listOf(recentSearchPresentation(recent).title to recent), requested)
        compose.onNodeWithText("Saved").assertDoesNotExist()

        saved = listOf(SavedSearchEntry("pin", requested.single().first, recent, savedAtEpochMs = 1))
        justSaved = "pin"
        compose.onNodeWithText("Saved").assertExists()
        justSaved = null
        compose.onNodeWithContentDescription("Rename ${requested.single().first}").assertExists()
    }

    @Test
    fun `a saved search's bookmark renames it in its row and never saves it again`() {
        val recent = search(RecentSearchKind.SOURCE)
        val pin = SavedSearchEntry("pin", "Evening", recent, savedAtEpochMs = 1)
        val saved = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        compose.setContent { MaterialTheme {
            TestRecents(
                searches = listOf(recent),
                saves = RecentSearchSaves(listOf(pin), null, { name, _ -> saved += name }, { id, name -> renamed += id to name }),
            )
        } }
        openSearches()
        compose.onNodeWithTag("Recent search:${recent.queryHash}")
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithText("Rename saved search").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("Evening")
        compose.onNode(hasSetTextAction()).performTextReplacement("Morning recipe")
        compose.onNode(hasSetTextAction()).performImeAction()

        assertEquals(listOf("pin" to "Morning recipe"), renamed)
        assertEquals(emptyList<String>(), saved)
        compose.onNodeWithContentDescription("Rename Evening").assertExists()
    }

    @Test
    fun `a tile with a long query still shows its bookmark`() {
        val long = search(RecentSearchKind.SOURCE).copy(query = Query(QueryMode.Source(SourceKey.PIXIV),
            List(12) { "very_long_tag_$it" }, emptyList(), SortMode.NEWEST, null, null))
        val saving = RecentSearchSaving(RecentSearchSaves(emptyList(), null, { _, _ -> }, { _, _ -> }), emptyMap(), null, {}, {})
        compose.setContent { MaterialTheme { RecentSearchTile(long, now = 200, onClick = {}, onLongPress = null, saving = saving) } }
        val bookmark = compose.onNodeWithContentDescription("Save search").fetchSemanticsNode()
        assertEquals(true, bookmark.size.width > 0)
    }

    @Test
    fun `source dropdown filters searches and FYP without changing multi-source replay`() {
        val pixiv = search(RecentSearchKind.SOURCE)
        val gelbooru = pixiv.copy(
            query = pixiv.query.copy(mode = QueryMode.Source(SourceKey.GELBOORU)),
            queryHash = "gelbooru", sources = listOf(SourceKey.GELBOORU),
        )
        val multi = search(RecentSearchKind.MULTI_SEARCH)
        val fyp = search(RecentSearchKind.FYP)
        var opened: RecentSearchEntry? = null
        compose.setContent { MaterialTheme {
            TestRecents(searches = listOf(pixiv, gelbooru, multi), fyp = listOf(fyp), onOpen = { opened = it })
        } }
        compose.onNodeWithContentDescription("Filter History by source").performClick()
        compose.onNodeWithContentDescription("Pixiv").performClick()
        openSearches()
        compose.onNodeWithTag("Recent search:${pixiv.queryHash}").assertExists()
        compose.onNodeWithTag("Recent search:${gelbooru.queryHash}").assertDoesNotExist()
        compose.onNodeWithTag("Recent search:${multi.queryHash}").performClick()
        assertEquals(multi, opened)
        compose.onNodeWithTag("Recent search:${fyp.queryHash}").assertExists()
        compose.onNodeWithText("Search", substring = false).performClick()
        compose.onNodeWithTag("Recent search:${fyp.queryHash}").assertDoesNotExist()
        compose.onNodeWithText("For You", substring = false).performClick()
        compose.onNodeWithTag("Recent search:${fyp.queryHash}").assertExists()
        compose.onNodeWithTag("Recent search:${pixiv.queryHash}").assertDoesNotExist()
        compose.onNodeWithText("All", substring = false).performClick()
        compose.onNodeWithContentDescription("Filter History by source").performClick()
        compose.onNodeWithContentDescription("All sources").performClick()
        compose.onNodeWithTag("Recent search:${gelbooru.queryHash}").assertExists()
    }

    @Test
    fun `filtered activity opens the selected post in its filtered section stream`() {
        val gelbooru = RecentPostEntry(
            testPost(source = SourceKey.GELBOORU, title = "Gelbooru post"),
            100L, ViewerStreamSource.SEARCH, null,
        )
        val pixiv = RecentPostEntry(
            testPost(title = "Pixiv post"), 101L, ViewerStreamSource.SEARCH, null,
        )
        var opened: Pair<List<RecentPostEntry>, Int>? = null
        compose.setContent { MaterialTheme {
            TestRecents(
                watched = listOf(gelbooru, pixiv),
                activity = listOf(RecentActivityEntry.Watched(gelbooru), RecentActivityEntry.Watched(pixiv)),
                onOpenPost = { entries, index -> opened = entries to index },
            )
        } }
        compose.onNodeWithContentDescription("Filter History by source").performClick()
        compose.onNode(hasContentDescription("Pixiv") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithTag(searchCardTestTag(gelbooru.post.id)).assertDoesNotExist()
        compose.onNodeWithTag(searchCardTestTag(pixiv.post.id)).performClick()
        assertEquals(listOf(pixiv) to 0, opened)
        compose.onNodeWithContentDescription("History actions").performClick()
        compose.onNodeWithText("Clear all History (all sources)").assertExists()
    }

    @Composable
    private fun TestRecents(
        searches: List<RecentSearchEntry> = emptyList(),
        watched: List<RecentPostEntry> = emptyList(),
        fyp: List<RecentSearchEntry> = emptyList(),
        activity: List<RecentActivityEntry> = emptyList(),
        onOpenPost: (List<RecentPostEntry>, Int) -> Unit = { _, _ -> },
        saves: RecentSearchSaves? = null,
        onOpen: (RecentSearchEntry) -> Unit = {},
        onOpenFyp: (RecentSearchEntry) -> Unit = {},
    ) {
        RecentsScreen(
            watchedPosts = watched, codexPosts = emptyList(), searches = searches,
            fypSearches = fyp, activity = activity,
            saves = saves,
            onRequestSaveToCodex = {}, onSaveToDevice = {},
            onGoToSearch = {},
            onOpenWatchedPost = onOpenPost, onOpenCodexPost = { _, _ -> }, onOpenSearch = onOpen, onOpenFypSearch = onOpenFyp, onClear = {},
        )
    }

    private fun openSearches() {
        compose.onNodeWithContentDescription("Filter History by section").performClick()
        compose.onNodeWithText("Searches").performClick()
    }

    private fun search(kind: RecentSearchKind): RecentSearchEntry {
        val source = kind == RecentSearchKind.SOURCE
        return RecentSearchEntry(
            query = Query(
                mode = if (source) QueryMode.Source(SourceKey.PIXIV) else QueryMode.Unified,
                includeTags = listOf("night"), excludeTags = listOf("comic"), sort = SortMode.NEWEST,
                dateRange = null, minScore = null,
            ),
            queryHash = "query-$kind", searchedAtEpochMs = 100, kind = kind,
            sources = if (source) listOf(SourceKey.PIXIV) else listOf(SourceKey.PIXIV, SourceKey.GELBOORU),
        )
    }
}
