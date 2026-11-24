package com.theoriacodex.app.viewer

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

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
