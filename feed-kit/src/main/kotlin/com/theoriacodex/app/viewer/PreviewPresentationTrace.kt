package com.theoriacodex.app.viewer

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asynchronous trace slices for one feed-preview presentation: a card showing one video, from its lease request until
 * its first rendered frame or until it goes away. scripts/trace_player_frames.py reports them per presentation.
 *
 * - Demand to first frame runs from [begin] (the lease request) to [onFirstFrame].
 * - Attach to first frame runs from the presentation's first surface attach to [onFirstFrame].
 * - A presentation that [end]s without a frame closes its slices inside an abandoned section, so a slice whose end
 *   falls inside one belongs to an abandoned presentation, and its length is how long that presentation waited.
 *
 * The card calls it on the main thread only. The slice names differ from the first-frame section the benchmarks count.
 */
internal class PreviewPresentationTrace {
    private var cookie = NO_PRESENTATION
    private var attachOpen = false
    private var attachSeen = false

    fun begin() {
        end()
        cookie = nextCookie.incrementAndGet()
        attachSeen = false
        Trace.beginAsyncSection(MediaTraceSections.PREVIEW_DEMAND_TO_FIRST_FRAME, cookie)
    }

    fun onSurfaceAttached() {
        if (cookie == NO_PRESENTATION || attachSeen) return
        attachSeen = true
        attachOpen = true
        Trace.beginAsyncSection(MediaTraceSections.PREVIEW_ATTACH_TO_FIRST_FRAME, cookie)
    }

    fun onFirstFrame() {
        if (cookie == NO_PRESENTATION) return
        closeSlices()
    }

    /** Ends the presentation; one still waiting for its first frame is recorded as abandoned. */
    fun end() {
        if (cookie == NO_PRESENTATION) return
        traceMediaSection(MediaTraceSections.PREVIEW_ABANDONED) { closeSlices() }
    }

    private fun closeSlices() {
        Trace.endAsyncSection(MediaTraceSections.PREVIEW_DEMAND_TO_FIRST_FRAME, cookie)
        if (attachOpen) Trace.endAsyncSection(MediaTraceSections.PREVIEW_ATTACH_TO_FIRST_FRAME, cookie)
        attachOpen = false
        cookie = NO_PRESENTATION
    }

    private companion object {
        const val NO_PRESENTATION = 0
        val nextCookie = AtomicInteger(NO_PRESENTATION)
    }
}
