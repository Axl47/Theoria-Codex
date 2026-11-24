package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.motion.TheoriaMotion

@Composable
internal fun BoxScope.ViewerFooterButton(
    selection: ViewerSelection,
    chromeVisible: Boolean,
    visible: Boolean,
    alignment: Alignment,
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    bottomRowHeight: Dp = VIEWER_POST_STRIP_HEIGHT,
    onClick: () -> Unit,
) {
    val footer = viewerFooterHeight(selection, chromeVisible, bottomRowHeight)
    val lift by animateDpAsState(footer, tween(TheoriaMotion.SHORT_MS), label = "Viewer footer button lift")
    ViewerChromeVisibility(
        visible,
        Modifier.align(alignment)
            .padding(horizontal = VIEWER_FOOTER_BUTTON_GAP)
            .padding(bottom = VIEWER_FOOTER_BUTTON_GAP)
            .offset { IntOffset(0, -lift.roundToPx()) },
    ) {
        IconButton(onClick = onClick, modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).testTag(testTag)) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White)
        }
    }
}

internal val LocalViewerSubtitleBottomLift = staticCompositionLocalOf { 0.dp }

@Composable
internal fun viewerFooterHeight(
    selection: ViewerSelection,
    chromeVisible: Boolean,
    bottomRowHeight: Dp,
    timelineInset: Dp = LocalViewerTimelineInset.current,
): Dp = timelineInset + if (chromeVisible) viewerFooterBarHeight(selection) + bottomRowHeight else 0.dp

@Composable
internal fun viewerFooterBarHeight(selection: ViewerSelection): Dp = when {
    selection.isAnimated -> viewerTimelineFooterHeight()
    selection.overviewItems.size > 1 -> VIEWER_TIMELINE_TRACK_HEIGHT
    else -> 0.dp
}

private val VIEWER_FOOTER_BUTTON_GAP = 12.dp
