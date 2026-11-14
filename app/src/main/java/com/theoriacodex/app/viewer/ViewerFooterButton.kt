package com.theoriacodex.app.viewer

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.motion.TheoriaMotion

/**
 * A round button in a lower corner of the Viewer ([alignment] picks the side), floating [VIEWER_FOOTER_BUTTON_GAP] above
 * the footer the current media shows. As the chrome hides and shows, the footer fades out of and into place, so the button
 * glides between the footer's edge and the screen's edge over the same span instead of jumping. Shared by rotation and
 * the slideshow.
 */
@Composable
internal fun BoxScope.ViewerFooterButton(
    selection: ViewerSelection,
    chromeVisible: Boolean,
    visible: Boolean,
    alignment: Alignment,
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
) {
    // The post strip's inset already follows the chrome; the timeline and page scrubber hide with it too.
    val footer = LocalViewerPostStripInset.current + if (chromeVisible) viewerFooterBarHeight(selection) else 0.dp
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

/** The bar the current media shows above the post strip: its timeline, or the page scrubber's track on multi-page posts. */
@Composable
private fun viewerFooterBarHeight(selection: ViewerSelection) = when {
    selection.isAnimated -> viewerTimelineFooterHeight()
    selection.overviewItems.size > 1 -> VIEWER_TIMELINE_TRACK_HEIGHT
    else -> 0.dp
}

/** The gap from the footer's edge, or the screen's when the footer is gone, and from the side. */
private val VIEWER_FOOTER_BUTTON_GAP = 12.dp
