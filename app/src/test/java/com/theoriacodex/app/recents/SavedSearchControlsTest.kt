package com.theoriacodex.app.recents

import com.theoriacodex.app.search.searchCardTestTag
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
    fun `recent search long press saves the named search`() {
        val recent = search(RecentSearchKind.SOURCE)
        val savedSearches = mutableStateOf(emptyList<SavedSearchEntry>())
        compose.setContent { MaterialTheme {
            TestRecents(
                searches = listOf(recent),
                onSave = { name, entry -> savedSearches.value = listOf(SavedSearchEntry("pin", name, entry, 100)) },
            )
        } }
        compose.onNodeWithText("Searches").performClick()
        compose.onNodeWithTag("Recent search:${recent.queryHash}")
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNode(hasSetTextAction()).performTextReplacement("Night recipe")
        compose.onNodeWithText("Save", substring = false).performClick()
        assertEquals(recent, savedSearches.value.single().search)
        assertEquals("Night recipe", savedSearches.value.single().name)

    }

    @Test
    fun `the visible save button saves the named search like the long press`() {
        val recent = search(RecentSearchKind.SOURCE)
        var saved: Pair<String, RecentSearchEntry>? = null
        compose.setContent { MaterialTheme {
            TestRecents(searches = listOf(recent), onSave = { name, entry -> saved = name to entry })
        } }
        compose.onNodeWithText("Searches").performClick()
        compose.onNodeWithContentDescription("Save search").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Morning recipe")
        compose.onNodeWithText("Save", substring = false).performClick()
        assertEquals("Morning recipe" to recent, saved)
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
        compose.onNodeWithContentDescription("Pixiv").performClick()
        compose.onNodeWithText("Searches").performClick()
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
        // The first "All" is the Recents tab; the second is the Searches filter.
        compose.onAllNodesWithText("All", substring = false)[1].performClick()
        compose.onNodeWithContentDescription("Filter Recents by source").performClick()
        compose.onNodeWithContentDescription("All sources").performClick()
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
        compose.onNodeWithContentDescription("Filter Recents by source").performClick()
        compose.onNode(hasContentDescription("Pixiv") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithTag(searchCardTestTag(gelbooru.post.id)).assertDoesNotExist()
        compose.onNodeWithTag(searchCardTestTag(pixiv.post.id)).performClick()
        assertEquals(listOf(pixiv) to 0, opened)
        compose.onNodeWithContentDescription("Recents actions").performClick()
        compose.onNodeWithText("Clear all Recents (all sources)").assertExists()
    }

    @Composable
    private fun TestRecents(
        searches: List<RecentSearchEntry> = emptyList(),
        watched: List<RecentPostEntry> = emptyList(),
        fyp: List<RecentSearchEntry> = emptyList(),
        activity: List<RecentActivityEntry> = emptyList(),
        onOpenPost: (List<RecentPostEntry>, Int) -> Unit = { _, _ -> },
        onSave: (String, RecentSearchEntry) -> Unit = { _, _ -> },
        onOpen: (RecentSearchEntry) -> Unit = {},
        onOpenFyp: (RecentSearchEntry) -> Unit = {},
    ) {
        RecentsScreen(
            watchedPosts = watched, codexPosts = emptyList(), searches = searches,
            fypSearches = fyp, activity = activity,
            onSaveSearch = onSave,
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
