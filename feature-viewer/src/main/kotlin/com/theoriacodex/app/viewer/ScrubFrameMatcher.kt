package com.theoriacodex.app.viewer

/**
 * Pairs a scrub preview player's frames with drag targets; call it on the main thread. A target waits until the
 * attached stream's index is read, since only then do seeks resolve to keyframes; its frame is the one at the
 * keyframe its seek resolved to; and a target that resolves to the frame already shown is answered with that frame,
 * because the player renders nothing for a seek to its current position.
 */
internal class ScrubFrameMatcher<F : Any> {
    private var attached = true
    private var indexed = false
    private var queuedMs: Long? = null
    private var expectedMs: Long? = null
    private var shown: Pair<Long, F>? = null

    /** What the player does for a target. */
    sealed interface Next<out F> {
        data class Seek(val positionMs: Long) : Next<Nothing>
        data class Answer<F>(val frame: F) : Next<F>
        data object Wait : Next<Nothing>
    }

    fun request(targetMs: Long): Next<F> {
        if (!indexed) {
            queuedMs = targetMs
            return Next.Wait
        }
        return seekOrAnswer(targetMs)
    }

    /** The stream's index is read; a queued target may seek now. A stopped stream's late timeline is ignored. */
    fun onIndexed(): Next<F> {
        if (!attached || indexed) return Next.Wait
        indexed = true
        return queuedMs?.let { targetMs -> queuedMs = null; seekOrAnswer(targetMs) } ?: Next.Wait
    }

    /** The pending seek resolved to [positionMs]; returns the shown frame when it is already there. */
    fun onSeekResolved(positionMs: Long): F? {
        expectedMs = positionMs
        return shown?.takeIf { (frameMs, _) -> frameMs == positionMs }?.second
    }

    /** A rendered frame; returns it when it answers the pending target. */
    fun onFrame(positionMs: Long, frame: F): F? {
        shown = positionMs to frame
        return frame.takeIf { positionMs == expectedMs }
    }

    /** The player stopped: its stream, index and shown frame are gone until [attach]. */
    fun detach() {
        attached = false
        queuedMs = null
        expectedMs = null
        shown = null
    }

    /** The stream is being prepared again; targets wait for its new index. */
    fun attach() {
        attached = true
        indexed = false
    }

    private fun seekOrAnswer(targetMs: Long): Next<F> {
        expectedMs = targetMs
        return shown?.takeIf { (frameMs, _) -> frameMs == targetMs }?.let { (_, frame) -> Next.Answer(frame) } ?: Next.Seek(targetMs)
    }
}
