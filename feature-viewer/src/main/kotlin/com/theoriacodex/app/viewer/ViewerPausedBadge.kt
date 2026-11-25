package com.theoriacodex.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

internal const val VIEWER_AUTO_ADVANCE_PAUSED_TAG = "viewer-auto-advance-paused"

@Composable
internal fun ViewerPausedBadge(paused: Boolean, content: @Composable () -> Unit) {
    Box(if (paused) Modifier.semantics { stateDescription = "Auto-advance paused" } else Modifier) {
        content()
        if (paused) {
            Icon(
                Icons.Default.Pause,
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(14.dp)
                    .background(LocalViewerAccent.current, CircleShape).padding(2.dp).testTag(VIEWER_AUTO_ADVANCE_PAUSED_TAG),
                tint = Color.Black,
            )
        }
    }
}
