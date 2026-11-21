package com.theoriacodex.app.journeys

import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performTouchInput
import com.theoriacodex.domain.model.Post
import org.junit.Test

class ViewerMultiPageJourneyTest : AppJourneyFixture() {
    override fun customizePost(index: Int, post: Post): Post {
        val pageCount = when (index) {
            MULTI_PAGE_POST -> 12
            STACKED_POST -> 3
            else -> return post
        }
        val pages = List(pageCount) { page ->
            solidImage("$index-$page", Color.HSVToColor(floatArrayOf(page * 360f / pageCount, 0.7f, 0.9f)))
        }
        return post.copy(preview = pages.first(), full = pages.first(), media = pages, mediaCount = pageCount)
    }

    @Test
    fun aPageDragTurnsThePageAndTheStripKeepsTheNextPostInReach() {
        openSearchPost(MULTI_PAGE_POST, RECENT_SEARCH)
        val scrubber = compose.onNodeWithTag("viewer-page-scrubber")
        compose.waitUntil(10_000) { scrubber.isDisplayed() }

        // The open post unfolds between its neighbours; a multi-page neighbour is marked before it is opened.
        compose.onNodeWithContentDescription("Go to page 1 of 12").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open post journey-$STACKED_POST").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open post journey-$NEXT_POST").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open post journey-$STACKED_POST").assert(hasStateDescription("3 pages"))

        scrubber.performTouchInput { down(percentOffset(0.04f, 0.5f)); moveTo(percentOffset(0.55f, 0.5f)) }
        compose.waitUntil(5_000) { compose.onNodeWithTag("viewer-scrub-preview").isDisplayed() }
        compose.onNodeWithText("+6").assertIsDisplayed()
        scrubber.performTouchInput { up() }

        compose.onNodeWithTag("viewer-scrub-preview").assertDoesNotExist()
        compose.waitUntil(5_000) { compose.onNodeWithText("7 / 12", substring = true).isDisplayed() }
        scrubber.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Page 7 of 12")))
        compose.waitUntil(5_000) { compose.onNodeWithContentDescription("Go to page 7 of 12").isDisplayed() }

        activate(compose.onNodeWithContentDescription("Open post journey-$NEXT_POST"))
        compose.waitUntil(10_000) { compose.onNodeWithContentDescription("Open post journey-$MULTI_PAGE_POST").isDisplayed() }
        compose.onNodeWithContentDescription("Open post journey-$MULTI_PAGE_POST").assert(hasStateDescription("12 pages"))
        scrubber.assertDoesNotExist()
    }


    private fun hasStateDescription(expected: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)

    private companion object {
        const val MULTI_PAGE_POST = 3
        const val NEXT_POST = 4
        const val STACKED_POST = 2
        const val RECENT_SEARCH = "journey:pages"
    }
}
