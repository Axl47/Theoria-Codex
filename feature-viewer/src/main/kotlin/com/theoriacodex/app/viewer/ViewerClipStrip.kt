package com.theoriacodex.app.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

private enum class ClipHandle { START, END }

@Composable
internal fun ViewerClipStrip(
    positionMs: () -> Long,
    episodeMs: Long,
    clip: EpisodeClipRange,
    onClipChange: (EpisodeClipRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = remember(clip, episodeMs) { episodeClipWindow(clip, episodeMs) }
    var draft by remember(clip) { mutableStateOf(clip) }
    val committed by rememberUpdatedState(clip)
    val position by produceState(positionMs()) {
        while (true) {
            value = positionMs()
            delay(PLAYHEAD_REFRESH_MS)
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val text = rememberTextMeasurer()
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).height(STRIP_TOTAL_HEIGHT).testTag(VIEWER_CLIP_STRIP_TAG)
            .semantics {
                contentDescription = "Clip"
                stateDescription = draft.label
                customActions = clipNudges(draft, episodeMs, onClipChange)
            },
    ) {
        val scale = StripScale(window, constraints.maxWidth.toFloat())
        val handleReachPx = with(LocalDensity.current) { HANDLE_REACH.toPx() }
        Canvas(
            Modifier.fillMaxSize().pointerInput(scale) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val handle = scale.handleNear(draft, down.position.x, handleReachPx) ?: return@awaitEachGesture
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                    fun moveTo(x: Float) {
                        val at = scale.msAt(x)
                        draft = if (handle == ClipHandle.START) draft.withStart(at) else draft.withEnd(at, episodeMs)
                    }
                    moveTo(slop.position.x)
                    val finished = horizontalDrag(slop.id) { change ->
                        change.consume()
                        moveTo(change.position.x)
                    }
                    if (!finished) draft = committed else if (draft != committed) onClipChange(draft)
                }
            },
        ) { drawClipStrip(scale, draft, position, accent, text) }
    }
}

private data class StripScale(val window: LongRange, val widthPx: Float) {
    private val span = (window.last - window.first).coerceAtLeast(1L).toFloat()
    fun xOf(ms: Long) = (ms - window.first) / span * widthPx
    fun msAt(x: Float) = window.first + (x.coerceIn(0f, widthPx) / widthPx * span).roundToLong()
    fun handleNear(clip: EpisodeClipRange, x: Float, reachPx: Float): ClipHandle? {
        val toStart = abs(x - xOf(clip.startMs))
        val toEnd = abs(x - xOf(clip.endMs))
        return when {
            minOf(toStart, toEnd) > reachPx -> null
            toStart < toEnd -> ClipHandle.START
            else -> ClipHandle.END
        }
    }
}

private fun DrawScope.drawClipStrip(scale: StripScale, clip: EpisodeClipRange, position: Long, accent: Color, text: TextMeasurer) {
    val top = LABEL_HEIGHT.toPx()
    val stripHeight = STRIP_HEIGHT.toPx()
    val corner = CornerRadius(CORNER.toPx())
    drawRoundRect(Color.White.copy(alpha = TRACK_ALPHA), Offset(0f, top), Size(size.width, stripHeight), corner)
    val start = scale.xOf(clip.startMs)
    val end = scale.xOf(clip.endMs)
    drawRoundRect(accent.copy(alpha = SELECTION_ALPHA), Offset(start, top), Size(end - start, stripHeight), corner)
    drawRoundRect(accent, Offset(start, top), Size(end - start, stripHeight), corner, Stroke(BORDER.toPx()))
    val handleWidth = HANDLE_WIDTH.toPx()
    listOf(start, end).forEach { x ->
        drawRoundRect(accent, Offset(x - handleWidth / 2, top - BORDER.toPx()), Size(handleWidth, stripHeight + 2 * BORDER.toPx()), corner)
    }
    if (position in scale.window) {
        val x = scale.xOf(position)
        drawLine(Color.White, Offset(x, top - PLAYHEAD_OVERHANG.toPx()), Offset(x, top + stripHeight + PLAYHEAD_OVERHANG.toPx()), PLAYHEAD_WIDTH.toPx())
    }
    drawTimeLabel(text, episodeClipTimeLabel(clip.startMs), start, 0f, bold = true)
    drawTimeLabel(text, episodeClipTimeLabel(clip.endMs), end, 0f, bold = true)
    val bottom = top + stripHeight + LABEL_GAP.toPx()
    drawTimeLabel(text, episodeClipTimeLabel(scale.window.first), 0f, bottom, bold = false, anchor = 0f)
    drawTimeLabel(text, episodeClipTimeLabel(scale.window.last), size.width, bottom, bold = false, anchor = 1f)
}

private fun DrawScope.drawTimeLabel(
    measurer: TextMeasurer,
    label: String,
    x: Float,
    y: Float,
    bold: Boolean,
    anchor: Float = 0.5f,
) {
    val style = TextStyle(color = Color.White.copy(alpha = if (bold) 1f else LABEL_ALPHA), fontSize = 11.sp,
        fontWeight = if (bold) FontWeight.SemiBold else null)
    val layout = measurer.measure(label, style)
    val left = (x - layout.size.width * anchor).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
    drawText(layout, topLeft = Offset(left, y))
}

private fun clipNudges(clip: EpisodeClipRange, episodeMs: Long, onClipChange: (EpisodeClipRange) -> Unit): List<CustomAccessibilityAction> {
    fun nudge(label: String, change: EpisodeClipRange.() -> EpisodeClipRange) = CustomAccessibilityAction(label) {
        val next = clip.change()
        if (next != clip) onClipChange(next)
        next != clip
    }
    return listOf(
        nudge("Start earlier") { withStart(startMs - NUDGE_MS) },
        nudge("Start later") { withStart(startMs + NUDGE_MS) },
        nudge("End earlier") { withEnd(endMs - NUDGE_MS, episodeMs) },
        nudge("End later") { withEnd(endMs + NUDGE_MS, episodeMs) },
    )
}

internal const val VIEWER_CLIP_STRIP_TAG = "viewer-clip-strip"
private val LABEL_HEIGHT = 18.dp
private val STRIP_HEIGHT = 38.dp
private val LABEL_GAP = 5.dp
private val STRIP_TOTAL_HEIGHT = 78.dp
private val CORNER = 6.dp
private val BORDER = 3.dp
private val HANDLE_WIDTH = 12.dp
private val HANDLE_REACH = 32.dp
private val PLAYHEAD_WIDTH = 2.dp
private val PLAYHEAD_OVERHANG = 6.dp
private const val TRACK_ALPHA = 0.14f
private const val SELECTION_ALPHA = 0.16f
private const val LABEL_ALPHA = 0.85f
private const val PLAYHEAD_REFRESH_MS = 100L
private const val NUDGE_MS = 1_000L
