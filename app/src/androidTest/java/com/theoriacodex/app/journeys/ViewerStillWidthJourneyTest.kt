package com.theoriacodex.app.journeys

import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** Portrait paged viewing: still images fill the screen width, videos keep their place below the top gap. */
class ViewerStillWidthJourneyTest : AppJourneyFixture() {
    override fun customizePost(index: Int, post: Post): Post = when (index) {
        GALLERY -> {
            val pages = List(3) { page -> solidImage("$index-$page", Color.HSVToColor(floatArrayOf(page * 120f, 0.7f, 0.9f))) }
            post.copy(preview = pages.first(), full = pages.first(), media = pages, mediaCount = pages.size)
        }
        // An unroutable address keeps the video loading, so its page stays laid out as a video.
        VIDEO -> {
            val video = ImageRef("http://10.255.255.1/journey.mp4", null, "video/mp4")
            post.copy(full = video, media = listOf(video), mediaCount = 1)
        }
        else -> post
    }

    @Test
    fun aGalleryPageFillsTheScreenWidthFromTheTop() {
        openSearchPost(GALLERY, RECENT_SEARCH)
        val (image, pager) = shown(posts.getValue(SourceKey.PIXIV)[GALLERY].title!!)
        assertEquals(compose.onRoot().fetchSemanticsNode().boundsInRoot.width, image.width)
        assertEquals(pager, image)
    }

    @Test
    fun aVideoKeepsItsTopGap() {
        openSearchPost(GALLERY, RECENT_SEARCH)
        activate(compose.onNodeWithContentDescription("Open post journey-$VIDEO"))
        val (media, pager) = shown("Media actions")
        var gap = 0
        scenario.onActivity { with(Density(it)) { gap = 40.dp.roundToPx() } }
        assertEquals(pager.top + gap, media.top)
        assertEquals(pager.width, media.width)
    }

    /** Bounds of the on-screen node described as [description], and of the media pager that pages it. */
    private fun shown(description: String): Pair<Rect, Rect> {
        val onScreen = { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().filter { it.boundsInRoot.left == 0f } }
        waitFor("Viewer shows $description at the left edge") { onScreen().isNotEmpty() }
        compose.waitForIdle()
        val node = onScreen().single()
        val pager = generateSequence(node.parent, SemanticsNode::parent).first { SemanticsProperties.IndexForKey in it.config }
        return node.boundsInRoot to pager.boundsInRoot
    }

    private companion object {
        const val GALLERY = 3
        const val VIDEO = 4
        const val RECENT_SEARCH = "journey:width"
    }
}
