package com.theoriacodex.app.viewer

import kotlin.math.abs
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.theoriacodex.app.ui.formatTimelineTime
import kotlin.math.roundToInt

val LocalViewerMediaAspectRatio = staticCompositionLocalOf<Float?> { null }

private val SCRUB_FRAME_LONG_EDGE = 224.dp
private val SCRUB_FRAME_PORTRAIT_HEIGHT = 168.dp
private val SCRUB_FRAME_MIN_EDGE = 72.dp

fun viewerScrubFrameSize(aspectRatio: Float?): DpSize {
    val ratio = aspectRatio?.takeIf { it > 0f } ?: (16f / 9f)
    return if (ratio >= 1f) {
        DpSize(SCRUB_FRAME_LONG_EDGE, (SCRUB_FRAME_LONG_EDGE / ratio).coerceAtLeast(SCRUB_FRAME_MIN_EDGE))
    } else {
        DpSize((SCRUB_FRAME_PORTRAIT_HEIGHT * ratio).coerceAtLeast(SCRUB_FRAME_MIN_EDGE), SCRUB_FRAME_PORTRAIT_HEIGHT)
    }
}

fun formatScrubDelta(deltaMs: Long): String =
    "${if (deltaMs < 0L) "-" else "+"}${formatTimelineTime(abs(deltaMs))}"

@Composable
internal fun ViewerScrubPreview(positionMs: Long, deltaMs: Long, progress: Float, content: @Composable () -> Unit) {
    ViewerScrubPopup(formatTimelineTime(positionMs), formatScrubDelta(deltaMs), progress) {
        Box(Modifier.viewerScrubFrame(viewerScrubFrameSize(LocalViewerMediaAspectRatio.current)), contentAlignment = Alignment.Center) { content() }
    }
}

fun Modifier.viewerScrubFrame(frameSize: DpSize): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return size(frameSize).shadow(12.dp, shape).background(Color.Black, shape).clip(shape)
}

@Composable
fun ViewerScrubPopup(readout: String, detail: String, progress: Float, frames: @Composable () -> Unit) {
    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(progress, gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
                (anchorBounds.left + anchorBounds.width * progress - popupContentSize.width / 2f).roundToInt()
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0),
            )
        }
    }
    Popup(popupPositionProvider = position, properties = PopupProperties(
        focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false,
    )) {
        Column(
            Modifier.testTag("viewer-scrub-preview"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            frames()
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(readout, color = Color.White, style = ViewerVeil.LargeReadout, fontWeight = FontWeight.Medium)
                Text(detail, Modifier.padding(bottom = 3.dp), color = Color.White.copy(alpha = ViewerVeil.SECONDARY_TEXT_ALPHA), style = ViewerVeil.Time)
            }
        }
    }
}
