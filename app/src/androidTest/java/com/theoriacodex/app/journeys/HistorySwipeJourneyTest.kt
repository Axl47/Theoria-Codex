package com.theoriacodex.app.journeys

import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test

/** History rows claim only the leftward swipe: left removes the entry with Undo in place, right still pages tabs. */
class HistorySwipeJourneyTest : AppJourneyFixture() {
    @Test
    fun swipingASearchLeftRemovesItUntilUndoAndSwipingRightChangesTab() {
        io {
            listOf("swipe-kept", "swipe-gone").forEach { tag ->
                container.data.recentsRepository.recordSearch(
                    Query(QueryMode.Source(SourceKey.PIXIV), listOf(tag), emptyList(), SortMode.POPULAR, null, null), tag,
                )
            }
        }
        launch()
        library(LibrarySection.History)
        openRecentsSection("Searches")
        val gone = compose.onNodeWithTag("Recent search:swipe-gone")
        compose.waitUntil(10_000) { gone.isDisplayed() }

        gone.performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { searches() == listOf("swipe-kept") }
        activate(compose.onNodeWithText("Undo"))
        compose.waitUntil(10_000) { searches().toSet() == setOf("swipe-kept", "swipe-gone") }

        val kept = compose.onNodeWithTag("Recent search:swipe-kept")
        compose.waitUntil(10_000) { kept.isDisplayed() }
        kept.performTouchInput { swipeRight() }
        // The pager moved to For You; a rightward drag never removes an entry.
        compose.waitUntil(10_000) { !kept.isDisplayed() }
        assertEquals(setOf("swipe-kept", "swipe-gone"), searches().toSet())
    }

    private fun searches() = io { container.data.recentsRepository.observeSearches().first().map { it.queryHash } }
}
