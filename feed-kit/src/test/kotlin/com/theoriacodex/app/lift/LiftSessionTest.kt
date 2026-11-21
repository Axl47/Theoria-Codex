package com.theoriacodex.app.lift

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.navigation.LiftRelease
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiftSessionTest {
    private val session = LiftSession(touchSlopPx = 8f)
    private val post = testPost()
    private val card = Rect(0f, 0f, 100f, 100f)
    private val save = LiftAction.Save("codex-1", "Cats")

    @Test
    fun `a lift starts from idle and follows the finger`() {
        assertTrue(session.start(post, card, Offset(50f, 50f)))
        session.move(Offset(200f, 300f))

        assertEquals(LiftPhase.Lifted(LiftedPost(post, card, Offset(50f, 50f), null, null)), session.phase)
        assertEquals(Offset(200f, 300f), session.pointer)
        assertFalse("a second card cannot lift while one is lifted", session.start(post, card, Offset.Zero))
    }

    @Test
    fun `releasing over a target acts once and lands the copy there`() {
        session.start(post, card, Offset(50f, 50f))
        session.targetAt = { save }

        assertEquals(LiftRelease.Acted, session.end(Offset(400f, 300f)))

        val settling = session.phase as LiftPhase.Settling
        assertEquals(save, settling.action)
        assertEquals(Offset(400f, 300f), settling.at)
        val drop = runBlocking { withTimeoutOrNull(1_000) { session.drops.first() } }
        assertEquals(LiftDrop(settling.lift, save), drop)
    }

    @Test
    fun `releasing within touch slop of the pick-up point opens the post menu`() {
        session.start(post, card, Offset(50f, 50f))

        assertEquals(LiftRelease.OpenMenu, session.end(Offset(55f, 54f)))
        assertEquals(LiftPhase.Idle, session.phase)
    }

    @Test
    fun `a release in place opens the menu even over a target`() {
        session.start(post, card, Offset(50f, 50f))
        session.targetAt = { save }

        assertEquals(LiftRelease.OpenMenu, session.end(Offset(53f, 50f)))
        assertEquals(LiftPhase.Idle, session.phase)
        assertNull(runBlocking { withTimeoutOrNull(50) { session.drops.first() } })
    }

    @Test
    fun `a finger hovers a target only once it moves past touch slop`() {
        session.start(post, card, Offset(50f, 50f))
        assertNull(session.hoverPointer)

        session.move(Offset(55f, 50f))
        assertNull(session.hoverPointer)

        session.move(Offset(70f, 50f))
        assertEquals(Offset(70f, 50f), session.hoverPointer)
    }

    @Test
    fun `releasing anywhere else cancels and returns the copy to its card`() {
        session.start(post, card, Offset(50f, 50f))
        session.move(Offset(150f, 200f))

        assertEquals(LiftRelease.Cancelled, session.end(Offset(150f, 200f)))
        val settling = session.phase as LiftPhase.Settling
        assertNull(settling.action)
        session.settled(settling)
        assertEquals(LiftPhase.Idle, session.phase)
    }

    @Test
    fun `a cancelled or disposed lift clears the overlay and ignores the rest of its gesture`() {
        session.start(post, card, Offset(50f, 50f))
        session.targetAt = { save }

        session.cancel()
        session.move(Offset(300f, 300f))

        assertEquals(LiftPhase.Idle, session.phase)
        assertEquals(Offset(50f, 50f), session.pointer)
        assertEquals(LiftRelease.Cancelled, session.end(Offset(300f, 300f)))
        assertNull(runBlocking { withTimeoutOrNull(50) { session.drops.first() } })
    }

    @Test
    fun `a lift started while another copy is landing survives that landing`() {
        session.start(post, card, Offset(50f, 50f))
        session.end(Offset(300f, 300f))
        val landing = session.phase as LiftPhase.Settling

        assertTrue(session.start(post, card, Offset(10f, 10f)))
        session.settled(landing)

        assertTrue(session.phase is LiftPhase.Lifted)
    }

    @Test
    fun `cancelling stops the pending work and clears it`() = runBlocking {
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            session.whilePending { awaitCancellation() }
        }
        assertEquals(job, session.pending)

        session.cancel()
        job.join()

        assertNull(session.pending)
    }

    @Test
    fun `only a lift in hand reports lifting`() {
        assertFalse(session.lifting)
        session.start(post, card, Offset(50f, 50f))
        assertTrue(session.lifting)
        session.end(Offset(300f, 300f))
        assertFalse(session.lifting)
    }
}
