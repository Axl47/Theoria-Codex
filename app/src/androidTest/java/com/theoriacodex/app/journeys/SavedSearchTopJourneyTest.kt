package com.theoriacodex.app.journeys

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSearchTopJourneyTest : AppJourneyFixture() {
    @Test
    fun aTopGelbooruSavedSearchOpensNewestFirstAndLoadMoreAppendsBelowTheFirstPage() {
        val query = Query(QueryMode.Source(SourceKey.GELBOORU), listOf("landscape"), emptyList(), SortMode.TOP, null, null)
        io { container.data.savedSearches.save("Gel faves", RecentSearchEntry(query, "gel-faves", 1)) }
        launch()
        waitFor("the launch new-posts check finishes", timeoutMillis = 15_000) { io { checked() } }
        container.registry.requests.clear()
        library(LibrarySection.Saved)
        activate(compose.onNodeWithText("Searches"))
        val gelbooru = posts.getValue(SourceKey.GELBOORU)
        waitFor("Searches shows the first page's newest post") { shows(gelbooru[19].id) }
        val firstPages = container.registry.requests.filter { it.source == SourceKey.GELBOORU && it.pageToken == null }
        assertTrue("Searches asked for $firstPages", firstPages.isNotEmpty() && firstPages.all { it.query.sort == SortMode.NEWEST })

        val secondPage = { container.registry.requests.any { it.source == SourceKey.GELBOORU && it.pageToken == "20" } }
        repeat(8) {
            if (secondPage()) return@repeat
            drag(up = true)
        }
        waitFor("scrolling loads Gelbooru's second page", condition = secondPage)
        waitFor("the second page is loaded") { listOf("40 posts", "60 posts").any { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() } }
        repeat(12) { drag(up = false) }
        compose.waitForIdle()

        waitFor("the first page's newest post is still on top after load-more") { shows(gelbooru[19].id) }
        assertTrue("a second-page post moved above the first page", listOf(39, 20).none { above(gelbooru[it].id, gelbooru[19].id) })
    }

    private fun drag(up: Boolean) {
        compose.onRoot().performTouchInput {
            val (from, to) = if (up) 0.75f to 0.3f else 0.3f to 0.75f
            swipe(Offset(centerX, height * from), Offset(centerX, height * to), durationMillis = 300L)
        }
        compose.waitForIdle()
    }

    private suspend fun checked() =
        container.data.uiRestoreRepository.observeSavedSearchNewPosts().first().attemptedAtEpochMs != null

    private fun shows(id: PostId) = compose.onAllNodesWithTag(searchCardTestTag(id)).fetchSemanticsNodes().isNotEmpty()

    private fun above(id: PostId, than: PostId): Boolean {
        val top = { target: PostId -> compose.onAllNodesWithTag(searchCardTestTag(target)).fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.top }
        val candidate = top(id) ?: return false
        val anchor = top(than) ?: return true
        return candidate < anchor
    }
}
