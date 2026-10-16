package com.theoriacodex.app.recents

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SavedSearchControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `recent search long press names a pin and picker supports rename replay and removal`() {
        val recent = search(RecentSearchKind.SOURCE)
        val pins = mutableStateOf(emptyList<SavedSearchEntry>())
        var opened: RecentSearchEntry? = null
        compose.setContent { MaterialTheme {
            TestRecents(
                searches = listOf(recent),
                saved = pins.value,
                onSave = { name, entry -> pins.value = listOf(SavedSearchEntry("pin", name, entry, 100)) },
                onRename = { id, name -> pins.value = pins.value.map { if (it.id == id) it.copy(name = name) else it } },
                onRemove = { id -> pins.value = pins.value.filterNot { it.id == id } },
                onOpen = { opened = it },
            )
        } }
        compose.onNodeWithText("Searches").performClick()
        compose.onNodeWithTag("Recent search:${recent.queryHash}")
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNode(hasSetTextAction()).performTextReplacement("Night recipe")
        compose.onNodeWithText("Pin", substring = false).performClick()
        assertEquals(recent, pins.value.single().search)

        compose.onNodeWithText("Saved searches").performClick()
        compose.onNodeWithContentDescription("Rename Night recipe").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Evening recipe")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.onNodeWithText("Evening recipe").performClick()
        assertEquals(recent, opened)
        compose.onNodeWithText("Saved searches").performClick()
        compose.onNodeWithContentDescription("Remove Evening recipe").performClick()
        assertTrue(pins.value.isEmpty())
    }

    @Test
    fun `picker replays For You through its dedicated route and Multi-Search through Search`() {
        val fyp = search(RecentSearchKind.FYP).copy(sourceTags = mapOf(
            SourceKey.PIXIV to listOf("夜"), SourceKey.GELBOORU to listOf("night"),
        ))
        val multi = search(RecentSearchKind.MULTI_SEARCH)
        val pins = listOf(SavedSearchEntry("fyp", "My For You", fyp, 100), SavedSearchEntry("multi", "Two sources", multi, 101))
        var opened: RecentSearchEntry? = null
        var openedFyp: RecentSearchEntry? = null
        compose.setContent { MaterialTheme {
            TestRecents(saved = pins, onOpen = { opened = it }, onOpenFyp = { openedFyp = it })
        } }

        compose.onNodeWithText("Saved searches").performClick()
        compose.onNodeWithText("My For You").performClick()
        assertEquals(fyp, openedFyp)
        assertEquals(null, opened)
        compose.onNodeWithText("Saved searches").performClick()
        compose.onNodeWithText("Two sources").performClick()
        assertEquals(multi, opened)
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
        compose.onNodeWithContentDescription("Filter Recents by source").performClick()
        compose.onNodeWithText("Pixiv").performClick()
        compose.onNodeWithText("Searches").performClick()
        compose.onNodeWithTag("Recent search:${pixiv.queryHash}").assertExists()
        compose.onNodeWithTag("Recent search:${gelbooru.queryHash}").assertDoesNotExist()
        compose.onNodeWithTag("Recent search:${multi.queryHash}").performClick()
        assertEquals(multi, opened)
        compose.onNodeWithText("FYP", substring = false).performClick()
        compose.onNodeWithTag("Recent search:${fyp.queryHash}").assertExists()
        compose.onNodeWithContentDescription("Filter Recents by source").performClick()
        compose.onNodeWithText("All sources").performClick()
        compose.onNodeWithText("Searches").performClick()
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
        compose.onNodeWithText("All", substring = false).performClick()
        compose.onNodeWithContentDescription("Filter Recents by source").performClick()
        compose.onNodeWithText("Pixiv").performClick()
        compose.onNodeWithText("Gelbooru post").assertDoesNotExist()
        compose.onNodeWithText("Pixiv post").performClick()
        assertEquals(listOf(pixiv) to 0, opened)
        compose.onNodeWithText("Clear section").assertExists()
    }

    @Composable
    private fun TestRecents(
        searches: List<RecentSearchEntry> = emptyList(),
        saved: List<SavedSearchEntry> = emptyList(),
        watched: List<RecentPostEntry> = emptyList(),
        fyp: List<RecentSearchEntry> = emptyList(),
        activity: List<RecentActivityEntry> = emptyList(),
        onOpenPost: (List<RecentPostEntry>, Int) -> Unit = { _, _ -> },
        onSave: (String, RecentSearchEntry) -> Unit = { _, _ -> },
        onRename: (String, String) -> Unit = { _, _ -> },
        onRemove: (String) -> Unit = {},
        onOpen: (RecentSearchEntry) -> Unit = {},
        onOpenFyp: (RecentSearchEntry) -> Unit = {},
    ) {
        RecentsScreen(
            watchedPosts = watched, codexPosts = emptyList(), searches = searches,
            fypSearches = fyp, activity = activity, savedSearches = saved,
            onSaveSearch = onSave, onRenameSavedSearch = onRename, onRemoveSavedSearch = onRemove,
            onRequestSaveToCodex = {}, onSaveToDevice = {}, onOpenCreatorProfile = {},
            onOpenLegacyCreatorProfile = {}, onAddIncludeTerm = { _, _ -> false }, onAddExcludeTerm = { _, _ -> false },
            onRemoveIncludeTerm = { _, _ -> }, onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = {},
            onOpenWatchedPost = onOpenPost, onOpenCodexPost = { _, _ -> }, onOpenSearch = onOpen, onOpenFypSearch = onOpenFyp, onClear = {},
        )
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
