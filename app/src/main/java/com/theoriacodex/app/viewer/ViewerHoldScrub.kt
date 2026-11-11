package com.theoriacodex.app.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Turns a horizontal drag during hold-to-speed-up into a timeline scrub. The active media page sets
 * [holding] while its long press lasts; the Viewer's recognizer then sends the drag here instead of to
 * the pager, and that page's [MediaTimelineBar] follows [phase] as if the finger held its handle.
 */
@Stable
internal class ViewerHoldScrub {
    /** Read once, when a gesture locks its direction, so it is not snapshot state. */
    var holding = false
    var phase by mutableStateOf<ViewerHoldScrubPhase>(ViewerHoldScrubPhase.Idle)
        private set

    fun drag(offsetPx: Float) { phase = ViewerHoldScrubPhase.Dragging(offsetPx) }

    fun end(released: Boolean) {
        if (phase is ViewerHoldScrubPhase.Dragging) phase = ViewerHoldScrubPhase.Ended(released)
    }
}

internal sealed interface ViewerHoldScrubPhase {
    data object Idle : ViewerHoldScrubPhase
    /** The finger is [offsetPx] from where the drag began; the handle moves the same distance. */
    data class Dragging(val offsetPx: Float) : ViewerHoldScrubPhase
    /** A released drag seeks; a cancelled one leaves playback where it was. */
    data class Ended(val released: Boolean) : ViewerHoldScrubPhase
}

/** The active page's hold scrub; null on other pages and outside the Viewer. */
internal val LocalViewerHoldScrub = compositionLocalOf<ViewerHoldScrub?> { null }
