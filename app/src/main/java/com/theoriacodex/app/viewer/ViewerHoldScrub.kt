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
        set(value) {
            field = value
            if (value) abandoned = false
        }
    var phase by mutableStateOf<ViewerHoldScrubPhase>(ViewerHoldScrubPhase.Idle)
        private set
    private var abandoned = false

    fun drag(offsetPx: Float) {
        if (!abandoned) phase = ViewerHoldScrubPhase.Dragging(offsetPx)
    }

    /**
     * The media that owned this hold went away mid-gesture. Cancels any scrub in progress and ignores
     * the rest of the drag, which now belongs to no timeline; the next hold starts clean.
     */
    fun abandon() {
        holding = false
        end(released = false)
        abandoned = true
    }

    fun end(released: Boolean) {
        val dragging = phase as? ViewerHoldScrubPhase.Dragging ?: return
        phase = ViewerHoldScrubPhase.Ended(released, dragging.offsetPx)
    }
}

internal sealed interface ViewerHoldScrubPhase {
    data object Idle : ViewerHoldScrubPhase
    /** The finger is [offsetPx] from where the drag began; the handle moves the same distance. */
    data class Dragging(val offsetPx: Float) : ViewerHoldScrubPhase
    /**
     * A released drag seeks to [offsetPx], the finger's last distance, so a drag that ended before the
     * timeline saw any [Dragging] state still lands; a cancelled one leaves playback where it was. Each
     * gesture's end is a new instance, so observers tell it from an earlier one by identity.
     */
    data class Ended(val released: Boolean, val offsetPx: Float) : ViewerHoldScrubPhase
}

/** The active page's hold scrub; null on other pages and outside the Viewer. */
internal val LocalViewerHoldScrub = compositionLocalOf<ViewerHoldScrub?> { null }
