package com.theoriacodex.app.viewer

internal class ScrubFrameMatcher<F : Any> {
    private var attached = true
    private var indexed = false
    private var queuedMs: Long? = null
    private var expectedMs: Long? = null
    private var shown: Pair<Long, F>? = null

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

    fun onIndexed(): Next<F> {
        if (!attached || indexed) return Next.Wait
        indexed = true
        return queuedMs?.let { targetMs -> queuedMs = null; seekOrAnswer(targetMs) } ?: Next.Wait
    }

    fun onSeekResolved(positionMs: Long): F? {
        expectedMs = positionMs
        return shown?.takeIf { (frameMs, _) -> frameMs == positionMs }?.second
    }

    fun onFrame(positionMs: Long, frame: F): F? {
        shown = positionMs to frame
        return frame.takeIf { positionMs == expectedMs }
    }

    fun detach() {
        attached = false
        queuedMs = null
        expectedMs = null
        shown = null
    }

    fun attach() {
        attached = true
        indexed = false
    }

    private fun seekOrAnswer(targetMs: Long): Next<F> {
        expectedMs = targetMs
        return shown?.takeIf { (frameMs, _) -> frameMs == targetMs }?.let { (_, frame) -> Next.Answer(frame) } ?: Next.Seek(targetMs)
    }
}
