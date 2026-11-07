package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.ScrubFrameMatcher.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrubFrameMatcherTest {
    private val frames = ScrubFrameMatcher<String>()

    @Test fun `a target waits for the index, then answers with the frame at the keyframe its seek resolved to`() {
        assertEquals(Next.Wait, frames.request(9_000))
        assertEquals(Next.Seek(9_000), frames.onIndexed())

        assertNull(frames.onSeekResolved(8_000))
        assertNull("a frame from an earlier seek", frames.onFrame(0, "start"))
        assertEquals("keyframe", frames.onFrame(8_000, "keyframe"))
    }

    @Test fun `a target resolving to the shown keyframe is answered with it, since nothing renders again`() {
        frames.onIndexed()
        frames.request(9_000)
        frames.onSeekResolved(8_000)
        frames.onFrame(8_000, "keyframe")

        assertEquals(Next.Seek(8_500), frames.request(8_500))
        assertEquals("keyframe", frames.onSeekResolved(8_000))
        assertEquals("the shown frame's own time needs no seek", Next.Answer("keyframe"), frames.request(8_000))
    }

    @Test fun `after stopping, a target waits for the re-attached stream's index, not the stopped stream's last timeline`() {
        frames.onIndexed()
        frames.request(9_000)
        frames.onSeekResolved(8_000)
        frames.onFrame(8_000, "keyframe")

        frames.detach()
        assertEquals(Next.Wait, frames.onIndexed())
        frames.attach()
        assertEquals(Next.Wait, frames.request(8_000))
        assertEquals("the shown frame went with the stream", Next.Seek(8_000), frames.onIndexed())
    }
}
