package com.theoriacodex.app.journeys

import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Test

class ViewerReadingModeJourneyTest : AppJourneyFixture() {
    override fun customizePost(index: Int, post: Post): Post {
        if (index != GALLERY) return post
        val pages = List(PAGES) { page -> solidImage("$index-$page", Color.HSVToColor(floatArrayOf(page * 360f / PAGES, 0.7f, 0.9f))) }
        return post.copy(preview = pages.first(), full = pages.first(), media = pages, mediaCount = PAGES)
    }

    @Test
    fun aGalleryChosenAsVerticalScrollsAsOneStripAndKeepsItsMode() {
        openGallery()
        activate(compose.onNodeWithContentDescription("Open media overview"))
        compose.onNodeWithText("Left to right").assertIsDisplayed()
        activate(compose.onNodeWithText("Vertical"))
        activate(compose.onNodeWithContentDescription("Close media overview"))
        waitFor("Viewer shows Page 1 after closing the media overview") { compose.onNodeWithContentDescription("Page 1").isDisplayed() }

        // Scrolling down reads on and tucks the controls away; scrolling back up brings them back.
        repeat(2) { compose.onRoot().performTouchInput { swipeUp(startY = centerY + height / 4f, endY = centerY - height / 4f) } }
        waitFor("Viewer controls are tucked away after scrolling down", timeoutMillis = 5_000) { !compose.onNodeWithContentDescription("Open media overview").isDisplayed() }
        compose.onNodeWithContentDescription("Page 1").assertDoesNotExist()
        compose.onRoot().performTouchInput { swipeDown(startY = centerY - height / 8f, endY = centerY + height / 8f) }
        waitFor("Viewer controls are back after scrolling up", timeoutMillis = 5_000) { compose.onNodeWithContentDescription("Open media overview").isDisplayed() }

        // The choice outlives the app, and a sideways swipe still moves to the next post.
        coldGraphRelaunch()
        showGallery()
        waitFor("Viewer strip page is shown after relaunch") { stripPageShown() }
        // Starts inside the screen: a swipe from the edge is the system's Back gesture, which the Viewer leaves alone.
        compose.onRoot().performTouchInput { swipeLeft(startX = width * 0.8f, endX = width * 0.2f) }
        waitFor("Viewer strip page is gone after swiping to the next post") { !stripPageShown() }
    }

    private fun openGallery() {
        io {
            container.data.recentsRepository.recordSearch(
                Query(QueryMode.Source(SourceKey.PIXIV), listOf("multi"), emptyList(), SortMode.POPULAR, null, null),
                RECENT_SEARCH,
            )
        }
        launch()
        showGallery()
    }

    private fun showGallery() {
        library(LibrarySection.History)
        openRecentsSection("Searches")
        activate(compose.onNodeWithTag("Recent search:$RECENT_SEARCH"))
        val card = compose.onNodeWithTag(searchCardTestTag(posts.getValue(SourceKey.PIXIV)[GALLERY].id))
        waitFor("gallery card is displayed") { card.isDisplayed() }
        activate(card)
    }

    /** A vertical strip labels each page "Page N"; reopening may land at the saved page rather than the first. */
    private fun stripPageShown() = (1..PAGES).any { compose.onNodeWithContentDescription("Page $it").isDisplayed() }

    private companion object {
        const val GALLERY = 3
        const val PAGES = 8
        const val RECENT_SEARCH = "journey:modes"
    }
}
