package com.theoriacodex.app.journeys

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.theoriacodex.app.viewer.mediaTestTagPart
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerVideoResumeJourneyTest : AppJourneyFixture() {
    override fun customizePost(index: Int, post: Post): Post {
        if (index != FIRST_VIDEO && index != SECOND_VIDEO) return post
        val video = assetVideo("resume-clip.mp4", "video-$index")
        return post.copy(preview = solidImage("poster-$index", Color.MAGENTA), full = video, media = listOf(video), width = 320, height = 180)
    }

    @Test
    fun aSwipedToVideoShowsItsFrameAtItsResumePointAndKeepsItThroughTheSettle_adr_0181() {
        openSearchPost(FIRST_VIDEO, RECENT_SEARCH)
        waitFor("the first video plays past its resume threshold") { (timelineFraction(FIRST_VIDEO) ?: 0f) > RESUMED_FRACTION }

        holdDrag(-HELD_DRAG)
        assertNeighbourShowsFrame(SECOND_VIDEO) { fraction -> fraction < FRESH_FRACTION }
        releaseDrag(-HELD_DRAG)
        waitFor("the second video is selected") { selectedPost() == "journey-$SECOND_VIDEO" }

        holdDrag(HELD_DRAG)
        assertNeighbourShowsFrame(FIRST_VIDEO) { fraction -> fraction > RESUMED_FRACTION }
        releaseDrag(HELD_DRAG)
        waitFor("the first video is selected again") { selectedPost() == "journey-$FIRST_VIDEO" }
        waitFor("the first video shows its timeline after the settle") { timelineFraction(FIRST_VIDEO) != null }
        assertTrue("the first video carries on from its resume point", checkNotNull(timelineFraction(FIRST_VIDEO)) > RESUMED_FRACTION)

        media().performTouchInput {
            down(Offset(width * 0.6f, height * 0.5f))
            moveBy(Offset(-width * SHORT_DRAG / 2f, 0f))
            moveBy(Offset(-width * SHORT_DRAG / 2f, 0f))
        }
        waitFor("video $SECOND_VIDEO shows its frame during a short drag") { showsVideoFrame(SECOND_VIDEO) }
        compose.mainClock.autoAdvance = false
        media().performTouchInput { advanceEventTime(RELEASE_PAUSE_MS); up() }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("the recoiling neighbour keeps its frame after a cancelled swipe", showsVideoFrame(SECOND_VIDEO))
        compose.mainClock.autoAdvance = true
        waitFor("the first video stays selected after the cancelled swipe") { selectedPost() == "journey-$FIRST_VIDEO" }
    }

    private fun holdDrag(fraction: Float) = media().performTouchInput {
        down(Offset(width * (0.5f - fraction / 2f), height * 0.5f))
        moveBy(Offset(width * fraction / 2f, 0f))
        moveBy(Offset(width * fraction / 2f, 0f))
    }

    private fun releaseDrag(fraction: Float) = media().performTouchInput { moveBy(Offset(width * fraction / 2f, 0f)); up() }

    private fun assertNeighbourShowsFrame(index: Int, expectedStart: (Float) -> Boolean) {
        waitFor("video $index renders a frame while the drag is held") { showsVideoFrame(index) }
        waitFor("video $index shows its timeline while the drag is held") { timelineFraction(index) != null }
        val fraction = checkNotNull(timelineFraction(index))
        assertTrue("video $index timeline starts at $fraction", expectedStart(fraction))
    }

    private fun media() = compose.onNodeWithContentDescription("Media actions", useUnmergedTree = true)

    private fun videoTag(index: Int) = "viewer_video_${posts.getValue(SourceKey.PIXIV)[index].id.mediaTestTagPart()}"

    private fun showsVideoFrame(index: Int): Boolean =
        compose.onNodeWithTag(videoTag(index), useUnmergedTree = true).isDisplayed() &&
            compose.onAllNodes(hasTestTag("viewer-video-placeholder") and hasAnyAncestor(hasTestTag(videoTag(index))), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()

    private fun timelineFraction(index: Int): Float? =
        compose.onAllNodes(
            hasProgressBarRangeInfo() and hasAnyAncestor(hasTestTag("viewer-timeline-track")) and hasAnyAncestor(hasTestTag(videoTag(index))),
            useUnmergedTree = true,
        )
            .fetchSemanticsNodes().firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current }

    private fun hasProgressBarRangeInfo() = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun selectedPost(): String? = compose.onAllNodes(isSelected()).fetchSemanticsNodes()
        .firstNotNullOfOrNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.removePrefix("Open post ")
                ?.takeIf { it.startsWith("journey-") }
        }

    private companion object {
        const val FIRST_VIDEO = 3
        const val SECOND_VIDEO = 4
        const val RESUMED_FRACTION = 0.1f
        const val FRESH_FRACTION = 0.02f
        const val HELD_DRAG = 0.4f
        const val SHORT_DRAG = 0.06f
        const val RELEASE_PAUSE_MS = 600L
        const val RECENT_SEARCH = "journey:video-resume"
    }
}
