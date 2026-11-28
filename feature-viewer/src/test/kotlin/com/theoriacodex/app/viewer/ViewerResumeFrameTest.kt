package com.theoriacodex.app.viewer

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ViewerResumeFrameTest {
    private val posts = (0..6).map { PostId(SourceKey.RULE34VIDEO, "video-$it") }
    private fun key(index: Int) = ViewerMediaKey(posts[index], 0)
    private var positions = ViewerVideoPositions().apply { retainAround(posts, 3) }

    private fun leave(index: Int, capturedMs: Long, releasedMs: Long, durationMs: Long = 30_000L): ImageBitmap {
        val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
        positions.offerLeavingFrame(key(index)) { deliver -> deliver(ViewerResumeFrame(capturedMs, durationMs, image)) }.use { positions.captureLeaving() }
        positions.save(key(index), releasedMs)
        return image
    }

    @Test
    fun `a page left during a swipe resumes at the frame captured when the swipe committed (ADR 0181)`() {
        val image = leave(3, capturedMs = 8_000L, releasedMs = 8_350L)

        assertEquals(8_000L, positions.position(key(3)))
        assertSame(image, positions.resumeFrame(key(3)))
    }

    @Test
    fun `a frame captured too long before the release, or after it, is dropped and the release position kept (ADR 0181)`() {
        leave(3, capturedMs = 8_000L, releasedMs = 12_000L)
        leave(4, capturedMs = 8_000L, releasedMs = 0L)

        assertEquals(12_000L, positions.position(key(3)))
        assertNull(positions.resumeFrame(key(3)))
        assertEquals(0L, positions.position(key(4)))
        assertNull(positions.resumeFrame(key(4)))
    }

    @Test
    fun `a peek that leaves the page at its frame keeps the frame, and a later move drops it (ADR 0181)`() {
        val image = leave(3, capturedMs = 8_000L, releasedMs = 8_350L)

        assertEquals(8_000L, positions.save(key(3), 8_000L))
        assertSame(image, positions.resumeFrame(key(3)))
        assertEquals(8_400L, positions.save(key(3), 8_400L))
        assertNull(positions.resumeFrame(key(3)))
    }

    @Test
    fun `a frame copied after its page already released is dropped (ADR 0181)`() {
        var deliver: (ViewerResumeFrame) -> Unit = {}
        positions.offerLeavingFrame(key(3)) { deliver = it }.use { positions.captureLeaving() }
        positions.save(key(3), 8_350L)
        deliver(ViewerResumeFrame(8_000L, 30_000L, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()))

        assertEquals(8_350L, positions.position(key(3)))
        assertNull(positions.resumeFrame(key(3)))
    }

    @Test
    fun `an episode left within its end margin, or reaching it during the settle, keeps no frame since it starts over (ADR 0181)`() {
        positions = ViewerVideoPositions(ViewerSeries.Watching()).apply { retainAround(posts, 3) }

        leave(3, capturedMs = 27_000L, releasedMs = 27_300L)
        leave(4, capturedMs = 24_800L, releasedMs = 25_200L)

        assertNull(positions.resumeFrame(key(3)))
        assertEquals(25_200L, positions.position(key(4)))
        assertNull(positions.resumeFrame(key(4)))
    }

    @Test
    fun `only the most recently left pages keep a frame (ADR 0181)`() {
        val frames = (0..5).map { leave(it, capturedMs = 5_000L, releasedMs = 5_000L) }

        assertNull(positions.resumeFrame(key(0)))
        assertNull(positions.resumeFrame(key(1)))
        assertSame(frames[2], positions.resumeFrame(key(2)))
        assertSame(frames[5], positions.resumeFrame(key(5)))
    }
}
