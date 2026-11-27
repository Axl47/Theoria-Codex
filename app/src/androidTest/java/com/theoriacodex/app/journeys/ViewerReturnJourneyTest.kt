package com.theoriacodex.app.journeys

import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerReturnJourneyTest : AppJourneyFixture() {
    @Test
    fun aViewerUnderASecondCardOpenedViewerStillClosesIntoItsCard_adr_0220() {
        openSearchPost(0, RECENT_SEARCH)
        val searchCard = compose.onNodeWithTag(searchCardTestTag(posts.getValue(SourceKey.PIXIV)[0].id))
        waitFor("Viewer A is open") { viewerShown() }
        activate(compose.onNodeWithContentDescription("Info"))
        activate(compose.onNodeWithContentDescription(CREATOR_LINK))
        val creatorCard = compose.onNodeWithTag(searchCardTestTag(posts.getValue(SourceKey.PIXIV)[1].id))
        waitFor("creator page shows another post") { creatorCard.isDisplayed() }

        activate(creatorCard)
        waitFor("Viewer B is open") { viewerShown() }
        pressSystemBack()
        waitFor("the creator page is back") { !viewerShown() && creatorCard.isDisplayed() }
        pressSystemBack()
        waitFor("Viewer A is shown again") { viewerShown() }

        compose.mainClock.autoAdvance = false
        pressSystemBack()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(MID_RETURN_MS)
        val returning = viewerShown()
        compose.mainClock.autoAdvance = true
        assertTrue("Viewer A left at once instead of closing into its card", returning)
        waitFor("Viewer A closed into its Search card") { !viewerShown() && searchCard.isDisplayed() }
    }

    private fun viewerShown() =
        compose.onAllNodesWithContentDescription("Media actions", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val RECENT_SEARCH = "journey:viewer-return"
        const val CREATOR_LINK = "Open Journey artist's profile"
        const val MID_RETURN_MS = 200L
    }
}
