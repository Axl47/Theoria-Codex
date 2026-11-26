package com.theoriacodex.app.viewer

internal class ScrubFrameMatcher<F : Any> {
    private var attached = true
    private var indexed = false
    private var queuedMs: Long? = null
    private var seeks = 0L
    private var awaiting: Long? = null
    private var shown: Pair<Long, F>? = null

    sealed interface Next<out F> {
        data class Seek(val positionMs: Long, val token: Long) : Next<Nothing>
        data object Wait : Next<Nothing>
    }

    fun request(targetMs: Long): Next<F> {
        awaiting = null
        if (!indexed) {
            queuedMs = targetMs
            return Next.Wait
        }
        return seek(targetMs)
    }

    fun onIndexed(): Next<F> {
        if (!attached || indexed) return Next.Wait
        indexed = true
        return queuedMs?.let { targetMs -> queuedMs = null; seek(targetMs) } ?: Next.Wait
    }

    fun onSettled(token: Long, generation: Long): F? {
        if (token != seeks) return null
        shown?.let { (shownGeneration, frame) -> if (shownGeneration >= generation) return frame }
        awaiting = generation
        return null
    }

    fun onFrame(generation: Long, frame: F): F? {
        shown = generation to frame
        return frame.takeIf { awaiting?.let { generation >= it } == true }?.also { awaiting = null }
    }

    fun detach() {
        attached = false
        queuedMs = null
        awaiting = null
        shown = null
        seeks++
    }

    fun attach() {
        attached = true
        indexed = false
    }

    private fun seek(targetMs: Long): Next.Seek = Next.Seek(targetMs, ++seeks)
}
