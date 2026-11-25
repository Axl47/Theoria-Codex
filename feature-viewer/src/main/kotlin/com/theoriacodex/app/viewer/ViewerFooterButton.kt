package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
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
    inStripRow: Boolean = false,
    slot: Int = 0,
    emphasized: Boolean = false,
    paused: Boolean = false,
    onClick: () -> Unit,
) {
    val inRow = inStripRow && chromeVisible
    val footer = viewerFooterButtonLift(selection, chromeVisible, bottomRowHeight, inRow)
    val glide by animateDpAsState(footer, tween(TheoriaMotion.SHORT_MS), label = "Viewer footer button lift")
    ViewerChromeVisibility(
        visible,
        Modifier.align(alignment)
            .padding(horizontal = VIEWER_FOOTER_BUTTON_GAP)
            .viewerFooterSlot(alignment, slot)
            .padding(bottom = VIEWER_FOOTER_BUTTON_GAP)
            .offset { IntOffset(0, -(if (inRow) footer else glide).roundToPx()) },
    ) {
        ViewerFooterIconButton(icon, contentDescription, testTag, emphasized, paused, onClick)
    }
}

@Composable
private fun viewerFooterButtonLift(selection: ViewerSelection, chromeVisible: Boolean, bottomRowHeight: Dp, inRow: Boolean): Dp =
    if (inRow) {
        viewerFooterHeight(selection, chromeVisible = true, bottomRowHeight = 0.dp) + VIEWER_POST_STRIP_CELL_CENTER - VIEWER_FOOTER_BUTTON_TOUCH / 2 - VIEWER_FOOTER_BUTTON_GAP
    } else {
        viewerFooterHeight(selection, chromeVisible, bottomRowHeight)
    }

private fun Modifier.viewerFooterSlot(alignment: Alignment, slot: Int): Modifier = padding(
    start = if (alignment == Alignment.BottomStart) VIEWER_FOOTER_BUTTON_TOUCH * slot else 0.dp,
    end = if (alignment == Alignment.BottomEnd) VIEWER_FOOTER_BUTTON_TOUCH * slot else 0.dp,
)

@Composable
private fun ViewerFooterIconButton(
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    emphasized: Boolean,
    paused: Boolean,
    onClick: () -> Unit,
) {
    val accent = LocalViewerAccent.current
    IconButton(
        onClick = onClick,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .then(if (emphasized) Modifier.border(1.5.dp, accent, CircleShape) else Modifier)
            .testTag(testTag),
    ) {
        ViewerPausedBadge(paused) { Icon(icon, contentDescription = contentDescription, tint = if (emphasized) accent else Color.White) }
    }
}

internal val LocalViewerSubtitleBottomLift = compositionLocalOf { 0.dp }

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
private val VIEWER_FOOTER_BUTTON_TOUCH = 48.dp
internal val VIEWER_STRIP_ROW_BUTTON_SLOT = VIEWER_FOOTER_BUTTON_GAP + VIEWER_FOOTER_BUTTON_TOUCH
internal val VIEWER_STRIP_ROW_BUTTON_WIDTH = VIEWER_FOOTER_BUTTON_TOUCH
