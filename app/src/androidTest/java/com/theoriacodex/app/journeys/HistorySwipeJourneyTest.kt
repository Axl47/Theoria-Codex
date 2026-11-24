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
        waitFor("swipe-gone recent search is displayed") { gone.isDisplayed() }

        gone.performTouchInput { swipeLeft() }
        waitFor("only swipe-kept remains in recent searches after the swipe") { searches() == listOf("swipe-kept") }
        activate(compose.onNodeWithText("Undo"))
        waitFor("swipe-gone is restored to recent searches after Undo") { searches().toSet() == setOf("swipe-kept", "swipe-gone") }

        val kept = compose.onNodeWithTag("Recent search:swipe-kept")
        waitFor("swipe-kept recent search is displayed") { kept.isDisplayed() }
        kept.performTouchInput { swipeRight() }
        // The pager moved to For You; a rightward drag never removes an entry.
        waitFor("swipe-kept is no longer displayed after the pager moved to For You") { !kept.isDisplayed() }
        assertEquals(setOf("swipe-kept", "swipe-gone"), searches().toSet())
    }

    private fun searches() = io { container.data.recentsRepository.observeSearches().first().map { it.queryHash } }
}
