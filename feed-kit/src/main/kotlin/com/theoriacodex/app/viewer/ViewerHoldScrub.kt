package com.theoriacodex.app.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
class ViewerHoldScrub {
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

sealed interface ViewerHoldScrubPhase {
    data object Idle : ViewerHoldScrubPhase
    data class Dragging(val offsetPx: Float) : ViewerHoldScrubPhase
    data class Ended(val released: Boolean, val offsetPx: Float) : ViewerHoldScrubPhase
}

val LocalViewerHoldScrub = compositionLocalOf<ViewerHoldScrub?> { null }
