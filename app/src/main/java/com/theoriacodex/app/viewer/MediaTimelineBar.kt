package com.theoriacodex.app.viewer

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.theoriacodex.app.ui.motion.TheoriaMotion
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal val LocalViewerInstantSeek = staticCompositionLocalOf { false }

/** Height of the controls row above a portrait timeline's track, shared by every kind of animated media. */
internal val VIEWER_TIMELINE_ROW_HEIGHT = 36.dp

/** Touch height of the track. With the row above, this stays inside the height gestures reserve for the footer. */
internal val VIEWER_TIMELINE_TRACK_HEIGHT = 34.dp

/**
 * The seekable timeline shared by video, GIF and ugoira. [leading] holds the player's own buttons.
 * [positionMs] is read while drawing, so playback redraws the track each frame but recomposes only
 * when the shown second changes.
 */
@Composable
fun MediaTimelineBar(
    positionMs: () -> Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    onSeekStarted: () -> Unit = {},
    onSeekFinished: (Long) -> Unit,
    onSeekCancelled: () -> Unit = {},
    preview: @Composable (Long) -> Unit,
    onInteractionActiveChanged: (Boolean) -> Unit = {},
    playbackRate: Float = 1f,
    leading: @Composable () -> Unit = {},
) {
    val instantSeek = LocalViewerInstantSeek.current
    val safeDuration = durationMs.coerceAtLeast(1L)
    val latestPosition by rememberUpdatedState(positionMs)
    val callbacks = rememberUpdatedState(
        TimelineSeekCallbacks(onSeekStarted, onSeekFinished, onSeekCancelled, onInteractionActiveChanged),
    )
    val scrub = remember { TimelineScrubState(callbacks) }
    val displayPosition: () -> Long = { if (scrub.active) scrub.positionMs else latestPosition().coerceIn(0L, safeDuration) }
    val shownSecond by remember(safeDuration) { derivedStateOf { displayPosition() / 1000L } }

    DisposableEffect(Unit) { onDispose(scrub::abandon) }

    val fraction = { (displayPosition().toDouble() / safeDuration.toDouble()).coerceIn(0.0, 1.0).toFloat() }
    val showingPreview = scrub.active && !instantSeek

    ViewerTimelineLayout(shownSecond * 1000L, safeDuration, playbackRate, showingPreview, modifier, leading) { trackModifier ->
        Box(
            modifier = trackModifier
                .height(VIEWER_TIMELINE_TRACK_HEIGHT)
                .onSizeChanged { scrub.widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .testTag("viewer-timeline-track")
                .timelineScrubGestures(
                    safeDuration, instantSeek,
                    onBegin = { x ->
                        scrub.begin(latestPosition().coerceIn(0L, safeDuration))
                        scrub.moveTo(x, safeDuration, instantSeek)
                    },
                    onMove = { x -> scrub.moveTo(x, safeDuration, instantSeek) },
                    onEnd = scrub::end,
                ),
            contentAlignment = Alignment.Center,
        ) {
            ViewerTimelineTrack(fraction, Modifier.fillMaxWidth().height(VIEWER_TIMELINE_TRACK_HEIGHT), emphasized = scrub.active)
            if (showingPreview) {
                ViewerScrubPreview(scrub.positionMs, scrub.positionMs - scrub.startMs, fraction()) { preview(scrub.positionMs) }
            }
        }
    }
}

/** What the timeline tells its player about a drag. */
private class TimelineSeekCallbacks(
    val onStarted: () -> Unit,
    val onFinished: (Long) -> Unit,
    val onCancelled: () -> Unit,
    val onInteractionActiveChanged: (Boolean) -> Unit,
)

/** A drag in progress: where it is now, where it began, and the track width that maps touches to time. */
private class TimelineScrubState(private val callbacks: State<TimelineSeekCallbacks>) {
    var active by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var startMs by mutableLongStateOf(0L)
        private set
    var widthPx = 1f

    fun begin(fromMs: Long) {
        startMs = fromMs
        active = true
        callbacks.value.onInteractionActiveChanged(true)
        callbacks.value.onStarted()
    }

    fun moveTo(x: Float, durationMs: Long, instantSeek: Boolean) {
        positionMs = ((x / widthPx).coerceIn(0f, 1f).toDouble() * durationMs).roundToLong().coerceIn(0L, durationMs)
        if (instantSeek) callbacks.value.onFinished(positionMs)
    }

    /** A released drag seeks; anything else leaves playback where it was. */
    fun end(released: Boolean) {
        if (active) {
            active = false
            if (released) callbacks.value.onFinished(positionMs) else callbacks.value.onCancelled()
        }
        callbacks.value.onInteractionActiveChanged(false)
    }

    /** The timeline left composition, so a held drag cannot complete. */
    fun abandon() = end(released = false)
}

/** One finger scrubs the track; a second finger or a stolen pointer cancels instead of seeking. */
private fun Modifier.timelineScrubGestures(
    durationKey: Long,
    instantSeekKey: Boolean,
    onBegin: (Float) -> Unit,
    onMove: (Float) -> Unit,
    onEnd: (released: Boolean) -> Unit,
): Modifier = pointerInput(durationKey, instantSeekKey) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var released = false
        try {
            onBegin(down.position.x)
            down.consume()
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size != 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break
                onMove(change.position.x)
                change.consume()
                if (!change.pressed) {
                    released = true
                    break
                }
            }
        } finally {
            onEnd(released)
        }
    }
}

/** Portrait gives the track its own full-width line; landscape keeps one line so the frame stays tall. */
@Composable
private fun ViewerTimelineLayout(
    positionMs: Long,
    durationMs: Long,
    playbackRate: Float,
    showingPreview: Boolean,
    modifier: Modifier,
    leading: @Composable () -> Unit,
    track: @Composable (Modifier) -> Unit,
) {
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Row(modifier.fillMaxWidth().height(VIEWER_TIMELINE_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Text(formatTimelineTime(positionMs), Modifier.padding(start = 4.dp, end = 12.dp), style = ViewerVeil.Time, maxLines = 1)
            track(Modifier.weight(1f))
            Text(formatTimelineTime(durationMs), Modifier.padding(start = 12.dp, end = 8.dp), style = ViewerVeil.Time, maxLines = 1)
            ViewerSpeedChip(playbackRate)
        }
        return
    }
    // The preview shows the time while scrubbing, so the row steps aside and nothing competes with it.
    val rowAlpha by animateFloatAsState(if (showingPreview) 0f else 1f, tween(TheoriaMotion.SHORT_MS), label = "Viewer timeline row")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(VIEWER_TIMELINE_ROW_HEIGHT).graphicsLayer { alpha = rowAlpha },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(4.dp))
            ViewerTimelineTime(positionMs, durationMs)
            Spacer(Modifier.weight(1f))
            ViewerSpeedChip(playbackRate)
        }
        track(Modifier.fillMaxWidth().padding(horizontal = 8.dp))
    }
}

@Composable
private fun ViewerTimelineTime(positionMs: Long, durationMs: Long) {
    Text(
        text = buildAnnotatedString {
            append(formatTimelineTime(positionMs))
            withStyle(SpanStyle(color = Color.White.copy(alpha = ViewerVeil.SECONDARY_TEXT_ALPHA))) {
                append(" / ${formatTimelineTime(durationMs)}")
            }
        },
        style = ViewerVeil.Time,
        maxLines = 1,
    )
}

/** Speed is only worth a mention when it differs from normal. */
@Composable
private fun ViewerSpeedChip(playbackRate: Float) {
    if (abs(playbackRate - 1f) < 0.01f) return
    Text(
        text = "${playbackRate.toString().removeSuffix(".0")}×",
        modifier = Modifier.padding(end = 8.dp)
            .background(Color.White.copy(alpha = 0.16f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = ViewerVeil.Time,
        maxLines = 1,
    )
}

/**
 * The one progress bar for all animated media. At rest it is thin with a small dot. While [emphasized]
 * it thickens and the dot becomes a handle, showing the drag took hold. Media that cannot seek omits the handle.
 * [fraction] is read while drawing; semantics follow it in whole percents so playback does not recompose the track.
 */
@Composable
internal fun ViewerTimelineTrack(
    fraction: () -> Float,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    showHandle: Boolean = true,
) {
    val accent = LocalViewerAccent.current
    val emphasis by animateFloatAsState(if (emphasized) 1f else 0f, tween(TheoriaMotion.SHORT_MS), label = "Viewer timeline emphasis")
    val percent by remember { derivedStateOf { (fraction().coerceIn(0f, 1f) * 100f).roundToInt() } }
    Canvas(modifier.progressSemantics(percent / 100f)) {
        val x = size.width * fraction().coerceIn(0f, 1f)
        val thickness = lerp(4.dp.toPx(), 10.dp.toPx(), emphasis)
        val gap = if (showHandle) lerp(0f, 6.dp.toPx(), emphasis) else 0f
        drawPill(accent, 0f, center.y, (x - gap).coerceAtLeast(0f), thickness)
        drawPill(ViewerVeil.InactiveTrack, (x + gap).coerceAtMost(size.width), center.y, size.width, thickness)
        if (showHandle) {
            val handleWidth = lerp(12.dp.toPx(), 4.dp.toPx(), emphasis)
            val handleHeight = lerp(12.dp.toPx(), 28.dp.toPx(), emphasis)
            drawPill(accent, x - handleWidth / 2f, center.y, x + handleWidth / 2f, handleHeight)
        }
    }
}

private fun DrawScope.drawPill(color: Color, left: Float, centerY: Float, right: Float, height: Float) {
    if (right <= left) return
    drawRoundRect(color, Offset(left, centerY - height / 2f), Size(right - left, height), CornerRadius(height / 2f))
}

@Composable
fun TimelinePlaybackButton(
    isPaused: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier.size(VIEWER_TIMELINE_ROW_HEIGHT),
    ) {
        Icon(
            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
            contentDescription = if (isPaused) "Play" else "Pause",
            tint = Color.White,
        )
    }
}

internal fun formatTimelineTime(valueMs: Long): String {
    val totalSeconds = (valueMs.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Display-only progress at the content edge; gestures pass through to the Viewer. [positionMs] is read
 * while drawing, so a position that changes every frame redraws the bar without recomposing its caller.
 */
@Composable
internal fun ViewerPassiveProgressBar(positionMs: () -> Long, durationMs: Long, modifier: Modifier = Modifier) {
    if (durationMs <= 0L) return
    val accent = LocalViewerAccent.current
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        val progress = (positionMs().toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
        drawRect(ViewerVeil.InactiveTrack)
        drawRect(accent, size = Size(size.width * progress, size.height))
    }
}
