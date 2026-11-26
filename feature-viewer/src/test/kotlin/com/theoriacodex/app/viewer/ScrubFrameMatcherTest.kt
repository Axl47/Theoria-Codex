package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.ScrubFrameMatcher.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrubFrameMatcherTest {
    private val frames = ScrubFrameMatcher<String>()

    @Test fun `a target waits for the index, then is answered by the frame its seek renders (ADR 0176)`() {
        assertEquals(Next.Wait, frames.request(9_000))
        assertEquals(Next.Seek(9_000, 1), frames.onIndexed())

        assertNull(frames.onSettled(token = 1, generation = 2))
        assertNull("a frame from an earlier seek", frames.onFrame(1, "start"))
        assertEquals("a frame of any time from this seek", "keyframe", frames.onFrame(2, "keyframe"))
    }

    @Test fun `a seek that leaves the player in place is answered by the shown frame, since nothing renders again (ADR 0176)`() {
        frames.onIndexed()
        frames.request(9_000)
        frames.onSettled(token = 1, generation = 2)
        frames.onFrame(2, "keyframe")

        assertEquals(Next.Seek(9_400, 2), frames.request(9_400))
        assertEquals("keyframe", frames.onSettled(token = 2, generation = 2))
    }

    @Test fun `a frame rendered before its seek settles answers on the settle (ADR 0176)`() {
        frames.onIndexed()
        frames.request(3_500)
        assertNull(frames.onFrame(5, "early"))
        assertEquals("early", frames.onSettled(token = 1, generation = 5))
    }

    @Test fun `a settled seek is answered by a later reset's frame, since only Media3 resets again without a new token (ADR 0176)`() {
        frames.onIndexed()
        frames.request(4_300)
        assertNull(frames.onSettled(token = 1, generation = 4))
        assertEquals("re-reset", frames.onFrame(5, "re-reset"))

        frames.request(4_800)
        assertNull(frames.onFrame(7, "early re-reset"))
        assertEquals("early re-reset", frames.onSettled(token = 2, generation = 6))
    }

    @Test fun `a newer request ignores the earlier seek's settle and frame (ADR 0176)`() {
        frames.onIndexed()
        frames.request(3_000)
        assertEquals(Next.Seek(4_000, 2), frames.request(4_000))

        assertNull(frames.onSettled(token = 1, generation = 2))
        assertNull(frames.onFrame(2, "old"))
        assertNull(frames.onSettled(token = 2, generation = 3))
        assertEquals("new", frames.onFrame(3, "new"))
    }

    @Test fun `after stopping, a target waits for the re-attached stream's index, not the stopped stream's last timeline`() {
        frames.onIndexed()
        frames.request(9_000)
        frames.onSettled(token = 1, generation = 2)
        frames.onFrame(2, "keyframe")

        frames.detach()
        assertEquals(Next.Wait, frames.onIndexed())
        frames.attach()
        assertEquals(Next.Wait, frames.request(8_000))
        assertEquals(Next.Seek(8_000, 3), frames.onIndexed())
        assertNull("the shown frame went with the stream", frames.onSettled(token = 3, generation = 2))
    }
}
