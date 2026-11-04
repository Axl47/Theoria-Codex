package com.theoriacodex.app.recents

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.viewer.LocalViewerReturnTransition
import com.theoriacodex.app.viewer.ViewerReturnTransition
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The default All view is a column of rows, where posts must still land back on their own cards. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
class RecentsViewerReturnTest {
    @get:Rule val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val first = entry("first", 0)
    private val neighbor = entry("neighbor", 1)
    private val otherRow = entry("other-row", 3)

    private fun entry(id: String, age: Long) = RecentPostEntry(
        post = Post(
            id = PostId(SourceKey.GELBOORU, id), preview = ImageRef(null, null, null), full = null, pageUrl = null,
            width = 800, height = 1000, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = null,
        ),
        viewedAtEpochMs = now - age, origin = ViewerStreamSource.RECENTS, originQueryHash = null, section = RecentPostSection.WATCHED,
    )

    private fun showAllView(): ViewerReturnTransition {
        val transition = ViewerReturnTransition()
        // A search between posts splits them into two separate rows.
        val search = RecentSearchEntry(
            query = Query(QueryMode.Unified, listOf("night"), emptyList(), SortMode.NEWEST, null, null),
            queryHash = "split", searchedAtEpochMs = now - 2, kind = RecentSearchKind.SOURCE, sources = listOf(SourceKey.GELBOORU),
        )
        val watched = listOf(first, neighbor, otherRow)
        compose.setContent {
            CompositionLocalProvider(LocalViewerReturnTransition provides transition) {
                RecentsScreen(
                    watchedPosts = watched, codexPosts = emptyList(), searches = listOf(search), fypSearches = emptyList(),
                    activity = listOf(
                        RecentActivityEntry.Watched(first), RecentActivityEntry.Watched(neighbor),
                        RecentActivityEntry.Search(search), RecentActivityEntry.Watched(otherRow),
                    ),
                    onRequestSaveToCodex = {}, onSaveToDevice = {}, onOpenCreatorProfile = {}, onOpenLegacyCreatorProfile = {},
                    onAddIncludeTerm = { _, _ -> false }, onAddExcludeTerm = { _, _ -> false },
                    onRemoveIncludeTerm = { _, _ -> }, onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = {},
                    onOpenWatchedPost = { _, _ -> }, onOpenCodexPost = { _, _ -> }, onOpenSearch = {}, onOpenFypSearch = {}, onClear = {},
                )
            }
        }
        compose.onNodeWithTag(searchCardTestTag(first.post.id)).performClick()
        return transition
    }

    private fun assertLandsOnCard(transition: ViewerReturnTransition, post: PostId) {
        compose.runOnIdle { transition.begin(post) }
        compose.waitForIdle()
        compose.runOnIdle { assertNotNull("$post should land on its card, not use the fallback", transition.targetBounds) }
    }

    @Test fun `closing the opened post lands on its own card`() = assertLandsOnCard(showAllView(), first.post.id)

    @Test fun `swiping to a neighbor in the row lands on that card`() = assertLandsOnCard(showAllView(), neighbor.post.id)

    @Test fun `swiping to a post in another row lands on that card`() = assertLandsOnCard(showAllView(), otherRow.post.id)
}
