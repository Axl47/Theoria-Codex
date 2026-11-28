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
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import com.theoriacodex.app.viewer.assumeTheEmulatorDecoderWorks
import com.theoriacodex.app.viewer.mediaTestTagPart
import com.theoriacodex.domain.diagnostics.DiagnosticSink
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ViewerVideoResumeJourneyTest : AppJourneyFixture() {
    private val playbackErrors = CopyOnWriteArrayList<Throwable>()
    private var previousSink: DiagnosticSink? = null

    @Before
    fun recordPlaybackErrors() {
        previousSink = Diagnostics.install { area, message, error ->
            if (area == "playback" && message.startsWith("Viewer video error") && error != null) playbackErrors += error
            previousSink?.record(area, message, error)
        }
    }

    @After
    fun restoreDiagnostics() {
        previousSink?.let(Diagnostics::install)
    }

    override fun customizePost(index: Int, post: Post): Post {
        if (index != FIRST_VIDEO && index != SECOND_VIDEO) return post
        val video = assetVideo("resume-clip.mp4", "video-$index")
        return post.copy(preview = solidImage("poster-$index", Color.MAGENTA), full = video, media = listOf(video), width = 320, height = 180)
    }

    @Test
    fun aSwipedToVideoShowsItsFrameAtItsResumePointAndKeepsItThroughTheSettle_adr_0181() = skippingTheEmulatorDecoder {
        openSearchPost(FIRST_VIDEO, RECENT_SEARCH)
        waitFor("the first video plays past its resume threshold") { (timelineFraction(FIRST_VIDEO) ?: 0f) > RESUMED_FRACTION }

        holdDrag(-HELD_DRAG)
        assertNeighbourShowsFrame(SECOND_VIDEO) { fraction -> fraction < FRESH_FRACTION }
        releaseDrag(-HELD_DRAG)
        waitFor("the second video is selected") { selectedPost() == "journey-$SECOND_VIDEO" }

        holdDrag(HELD_DRAG)
        assertNeighbourShowsFrame(FIRST_VIDEO, resumed = true) { fraction -> fraction > RESUMED_FRACTION }
        releaseDrag(HELD_DRAG)
        waitFor("the first video is selected again") { selectedPost() == "journey-$FIRST_VIDEO" }
        waitFor("the first video shows its timeline after the settle") { timelineFraction(FIRST_VIDEO) != null }
        assertTrue("the first video carries on from its resume point", checkNotNull(timelineFraction(FIRST_VIDEO)) > RESUMED_FRACTION)

        peekSecondVideoAndCancel("a short drag")
        peekSecondVideoAndCancel("a second short drag")
    }

    private fun peekSecondVideoAndCancel(drag: String) {
        media().performTouchInput {
            down(Offset(width * 0.6f, height * 0.5f))
            moveBy(Offset(-width * SHORT_DRAG / 2f, 0f))
            moveBy(Offset(-width * SHORT_DRAG / 2f, 0f))
        }
        var blackShown = false
        waitFor("video $SECOND_VIDEO shows its frame during $drag") {
            blackShown = blackShown || placeholders(SECOND_VIDEO, PLACEHOLDER_TAG)
            showsVideoFrame(SECOND_VIDEO)
        }
        assertTrue("video $SECOND_VIDEO showed black before its resume frame during $drag", !blackShown)
        compose.mainClock.autoAdvance = false
        media().performTouchInput { advanceEventTime(RELEASE_PAUSE_MS); up() }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("the recoiling neighbour keeps its frame after $drag is cancelled", showsVideoFrame(SECOND_VIDEO))
        compose.mainClock.autoAdvance = true
        waitFor("the first video stays selected after $drag is cancelled") { selectedPost() == "journey-$FIRST_VIDEO" }
        waitFor("video $SECOND_VIDEO leaves the screen after $drag is cancelled") { !compose.onNodeWithTag(videoTag(SECOND_VIDEO), useUnmergedTree = true).isDisplayed() }
    }

    private fun skippingTheEmulatorDecoder(journey: () -> Unit) = try {
        journey()
    } catch (failure: Throwable) {
        assumeTheEmulatorDecoderWorks(playbackErrors.firstNotNullOfOrNull { (it.cause as? MediaCodecDecoderException)?.codecInfo?.name })
        throw failure
    }

    private fun holdDrag(fraction: Float) = media().performTouchInput {
        down(Offset(width * (0.5f - fraction / 2f), height * 0.5f))
        moveBy(Offset(width * fraction / 2f, 0f))
        moveBy(Offset(width * fraction / 2f, 0f))
    }

    private fun releaseDrag(fraction: Float) = media().performTouchInput { moveBy(Offset(width * fraction / 4f, 0f)); up() }

    private fun assertNeighbourShowsFrame(index: Int, resumed: Boolean = false, expectedStart: (Float) -> Boolean) {
        var posterShown = false
        var blackShown = false
        waitFor("video $index renders a frame while the drag is held") {
            posterShown = posterShown || placeholders(index, POSTER_TAG)
            blackShown = blackShown || placeholders(index, PLACEHOLDER_TAG)
            showsVideoFrame(index)
        }
        assertTrue("video $index showed its poster while loading", !resumed || !posterShown)
        assertTrue("video $index showed black before its resume frame", !resumed || !blackShown)
        waitFor("video $index shows its timeline while the drag is held") { timelineFraction(index) != null }
        val fraction = checkNotNull(timelineFraction(index))
        assertTrue("video $index timeline starts at $fraction", expectedStart(fraction))
    }

    private fun media() = compose.onNodeWithContentDescription("Media actions", useUnmergedTree = true)

    private fun videoTag(index: Int) = "viewer_video_${posts.getValue(SourceKey.PIXIV)[index].id.mediaTestTagPart()}"

    private fun showsVideoFrame(index: Int): Boolean =
        compose.onNodeWithTag(videoTag(index), useUnmergedTree = true).isDisplayed() &&
            !placeholders(index, POSTER_TAG) && !placeholders(index, PLACEHOLDER_TAG) && !placeholders(index, RESUME_FRAME_TAG)

    private fun placeholders(index: Int, tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(videoTag(index))), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

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
        const val POSTER_TAG = "viewer-video-poster"
        const val RESUME_FRAME_TAG = "viewer-video-resume-frame"
        const val PLACEHOLDER_TAG = "viewer-video-placeholder"
        const val RECENT_SEARCH = "journey:video-resume"
    }
}
