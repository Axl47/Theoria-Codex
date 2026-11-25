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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.theoriacodex.app.ui.formatTimelineTime
import com.theoriacodex.app.ui.motion.TheoriaMotion
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

val LocalViewerInstantSeek = staticCompositionLocalOf { false }

val VIEWER_TIMELINE_ROW_HEIGHT = 36.dp

val VIEWER_TIMELINE_TRACK_HEIGHT = 34.dp

@Composable
fun MediaTimelineBar(
    positionMs: () -> Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    bufferedPositionMs: (() -> Long)? = null,
    onSeekStarted: () -> Unit = {},
    onSeekMoved: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    onSeekCancelled: () -> Unit = {},
    preview: @Composable (Long) -> Unit,
    onInteractionActiveChanged: (Boolean) -> Unit = {},
    playbackRate: Float = 1f,
    showSpeedChip: Boolean = true,
    leading: @Composable () -> Unit = {},
    seekable: Boolean = true,
) {
    val instantSeek = LocalViewerInstantSeek.current
    val safeDuration = durationMs.coerceAtLeast(1L)
    val latestPosition by rememberUpdatedState(positionMs)
    val latestBuffered by rememberUpdatedState(bufferedPositionMs)
    val callbacks = rememberUpdatedState(
        TimelineSeekCallbacks(onSeekStarted, onSeekMoved, onSeekFinished, onSeekCancelled, onInteractionActiveChanged),
    )
    val scrub = remember { TimelineScrubState(callbacks) }
    val displayPosition: () -> Long = { if (scrub.active) scrub.positionMs else latestPosition().coerceIn(0L, safeDuration) }
    val shownSecond by remember(safeDuration) { derivedStateOf { displayPosition() / 1000L } }

    DisposableEffect(Unit) { onDispose(scrub::abandon) }
    FollowHoldScrub(scrub, safeDuration, instantSeek, latestPosition)

    val fraction = { (displayPosition().toDouble() / safeDuration.toDouble()).coerceIn(0.0, 1.0).toFloat() }
    val bufferedFraction: (() -> Float)? = if (durationMs > 0L && bufferedPositionMs != null) {
        {
            val buffered = latestBuffered?.invoke()?.coerceIn(0L, safeDuration) ?: 0L
            (buffered.toDouble() / safeDuration.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
    } else null
    val showingPreview = scrub.active && !instantSeek

    ViewerTimelineLayout(shownSecond * 1000L, safeDuration, playbackRate, showSpeedChip, showingPreview, modifier, leading) { trackModifier ->
        Box(
            modifier = trackModifier
                .height(VIEWER_TIMELINE_TRACK_HEIGHT)
                .onSizeChanged { scrub.widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .testTag("viewer-timeline-track")
                .then(if (!seekable) Modifier else Modifier.timelineScrubGestures(
                    safeDuration, instantSeek,
                    onBegin = { x ->
                        scrub.begin(latestPosition().coerceIn(0L, safeDuration))
                        scrub.moveTo(x, safeDuration, instantSeek)
                    },
                    onMove = { x -> scrub.moveTo(x, safeDuration, instantSeek) },
                    onEnd = scrub::end,
                )),
            contentAlignment = Alignment.Center,
        ) {
            ViewerTimelineTrack(
                fraction = fraction,
                modifier = Modifier.fillMaxWidth().height(VIEWER_TIMELINE_TRACK_HEIGHT),
                bufferedFraction = bufferedFraction,
                emphasized = scrub.active,
            )
            if (showingPreview) {
                ViewerScrubPreview(scrub.positionMs, scrub.positionMs - scrub.startMs, fraction()) { preview(scrub.positionMs) }
            }
        }
    }
}

@Composable
private fun FollowHoldScrub(scrub: TimelineScrubState, durationMs: Long, instantSeek: Boolean, positionMs: () -> Long) {
    val holdScrub = LocalViewerHoldScrub.current ?: return
    val duration by rememberUpdatedState(durationMs)
    val instant by rememberUpdatedState(instantSeek)
    val position by rememberUpdatedState(positionMs)
    LaunchedEffect(holdScrub, scrub) {
        val follower = HoldScrubFollower(scrub, { duration }, { instant }, { position() })
        val staleEnd = holdScrub.phase as? ViewerHoldScrubPhase.Ended
        snapshotFlow { holdScrub.phase }.collect { phase ->
            when (phase) {
                is ViewerHoldScrubPhase.Dragging -> follower.follow(phase.offsetPx)
                is ViewerHoldScrubPhase.Ended -> if (phase !== staleEnd) follower.finish(phase)
                ViewerHoldScrubPhase.Idle -> Unit
            }
        }
    }
}

private class HoldScrubFollower(
    private val scrub: TimelineScrubState,
    private val duration: () -> Long,
    private val instant: () -> Boolean,
    private val position: () -> Long,
) {
    private var originX: Float? = null

    fun follow(offsetPx: Float) {
        if (originX == null && scrub.widthPx > 1f) {
            val start = position().coerceIn(0L, duration())
            scrub.begin(start)
            originX = start.toFloat() / duration() * scrub.widthPx
        }
        originX?.let { scrub.moveTo(it + offsetPx, duration(), instant()) }
    }

    fun finish(ended: ViewerHoldScrubPhase.Ended) {
        follow(ended.offsetPx)
        if (originX == null) return
        originX = null
        scrub.end(ended.released)
    }
}

private class TimelineSeekCallbacks(
    val onStarted: () -> Unit,
    val onMoved: (Long) -> Unit,
    val onFinished: (Long) -> Unit,
    val onCancelled: () -> Unit,
    val onInteractionActiveChanged: (Boolean) -> Unit,
)

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
        if (instantSeek) callbacks.value.onMoved(positionMs)
    }

    fun end(released: Boolean) {
        if (active) {
            active = false
            if (released) callbacks.value.onFinished(positionMs) else callbacks.value.onCancelled()
        }
        callbacks.value.onInteractionActiveChanged(false)
    }

    fun abandon() = end(released = false)
}

fun Modifier.timelineScrubGestures(
    key1: Any?,
    key2: Any?,
    onBegin: (Float) -> Unit,
    onMove: (Float) -> Unit,
    onEnd: (released: Boolean) -> Unit,
): Modifier = pointerInput(key1, key2) {
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
                change.consume()
                if (!change.pressed) {
                    released = true
                    break
                }
                onMove(change.position.x)
            }
        } finally {
            onEnd(released)
        }
    }
}

@Composable
@ReadOnlyComposable
fun viewerTimelineOnOneLine(): Boolean = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

@Composable
@ReadOnlyComposable
fun viewerTimelineFooterHeight(): Dp = if (viewerTimelineOnOneLine()) VIEWER_TIMELINE_ROW_HEIGHT else VIEWER_TIMELINE_HEIGHT

@Composable
private fun ViewerTimelineLayout(
    positionMs: Long,
    durationMs: Long,
    playbackRate: Float,
    showSpeedChip: Boolean,
    showingPreview: Boolean,
    modifier: Modifier,
    leading: @Composable () -> Unit,
    track: @Composable (Modifier) -> Unit,
) {
    if (viewerTimelineOnOneLine()) {
        Row(modifier.fillMaxWidth().height(VIEWER_TIMELINE_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Text(formatTimelineTime(positionMs), Modifier.padding(start = 4.dp, end = 12.dp), style = ViewerVeil.Time, maxLines = 1)
            track(Modifier.weight(1f))
            Text(formatTimelineTime(durationMs), Modifier.padding(start = 12.dp, end = 8.dp), style = ViewerVeil.Time, maxLines = 1)
            if (showSpeedChip) ViewerSpeedChip(playbackRate)
        }
        return
    }
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
            if (showSpeedChip) ViewerSpeedChip(playbackRate)
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

@Composable
fun ViewerTimelineTrack(
    fraction: () -> Float,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    showHandle: Boolean = true,
    bufferedFraction: (() -> Float)? = null,
) {
    val accent = LocalViewerAccent.current
    val emphasis by animateFloatAsState(if (emphasized) 1f else 0f, tween(TheoriaMotion.SHORT_MS), label = "Viewer timeline emphasis")
    val percent by remember { derivedStateOf { (fraction().coerceIn(0f, 1f) * 100f).roundToInt() } }
    Canvas(modifier.progressSemantics(percent / 100f)) {
        val x = size.width * fraction().coerceIn(0f, 1f)
        val thickness = lerp(4.dp.toPx(), 10.dp.toPx(), emphasis)
        val gap = if (showHandle) lerp(0f, 6.dp.toPx(), emphasis) else 0f
        val trailingStart = (x + gap).coerceAtMost(size.width)
        drawPill(ViewerVeil.InactiveTrack, trailingStart, center.y, size.width, thickness)
        viewerBufferedTrackEnd(bufferedFraction?.invoke(), trailingStart, size.width)?.let { bufferedEnd ->
            drawPill(accent().copy(alpha = VIEWER_TIMELINE_BUFFERED_ALPHA), trailingStart, center.y, bufferedEnd, thickness)
        }
        drawPill(accent(), 0f, center.y, (x - gap).coerceAtLeast(0f), thickness)
        if (showHandle) {
            val handleWidth = lerp(12.dp.toPx(), 4.dp.toPx(), emphasis)
            val handleHeight = lerp(12.dp.toPx(), 28.dp.toPx(), emphasis)
            drawPill(accent(), x - handleWidth / 2f, center.y, x + handleWidth / 2f, handleHeight)
        }
    }
}

internal fun viewerBufferedTrackEnd(bufferedFraction: Float?, trailingStart: Float, width: Float): Float? {
    val fraction = bufferedFraction?.takeIf { it.isFinite() } ?: return null
    val end = (width * fraction.coerceIn(0f, 1f)).coerceAtMost(width)
    return end.takeIf { it > trailingStart }
}

internal const val VIEWER_TIMELINE_BUFFERED_ALPHA = 0.42f

fun DrawScope.drawPill(color: Color, left: Float, centerY: Float, right: Float, height: Float) {
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

@Composable
fun ViewerPassiveProgressBar(positionMs: () -> Long, durationMs: Long, modifier: Modifier = Modifier) {
    if (durationMs <= 0L) return
    val accent = LocalViewerAccent.current
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        val progress = (positionMs().toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
        drawRect(ViewerVeil.InactiveTrack)
        drawRect(accent(), size = Size(size.width * progress, size.height))
    }
}

class ViewerTimelineSeek(
    val onStarted: () -> Unit,
    val onMoved: (Long) -> Unit,
    val onFinished: (Long) -> Unit,
    val onCancelled: () -> Unit,
    val preview: @Composable (Long) -> Unit,
)

@Composable
fun ViewerPlaybackTimeline(
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    positionMs: () -> Long,
    durationMs: Long,
    bufferedPositionMs: (() -> Long)? = null,
    seek: ViewerTimelineSeek? = null,
    onToggled: () -> Unit = {},
) {
    ViewerTimelineVeil {
        ViewerPlaybackFooter {
            MediaTimelineBar(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                onSeekStarted = seek?.onStarted ?: {},
                onSeekMoved = seek?.onMoved ?: {},
                onSeekFinished = seek?.onFinished ?: {},
                onSeekCancelled = seek?.onCancelled ?: {},
                preview = seek?.preview ?: {},
                onInteractionActiveChanged = if (seek != null) events.onTimelineInteractionActiveChanged else ({}),
                playbackRate = controls.playbackRate,
                showSpeedChip = controls.showSpeedChip,
                leading = {
                    if (controls.showLeadingPlaybackButton) {
                        TimelinePlaybackButton(isPaused = !controls.isPlaying, onToggle = {
                            events.onTogglePlayback()
                            onToggled()
                            events.onTimelineInteractionActiveChanged(true)
                            events.onTimelineInteractionActiveChanged(false)
                        })
                    }
                },
                seekable = seek != null,
            )
        }
    }
}

@Composable
fun ViewerPendingTimeline(controls: ViewerRendererControls, events: ViewerRendererEvents, modifier: Modifier) {
    val expected = controls.expectedDurationMs?.takeIf { it > 0L && (controls.isActive || controls.isPreloading) } ?: return
    ViewerChromeVisibility(controls.showControls, modifier) {
        ViewerPlaybackTimeline(controls, events, { 0L }, expected)
    }
}

@Composable
fun ViewerMediaPassiveProgress(positionMs: () -> Long, durationMs: Long, aspectRatio: Float?) {
    val transform = LocalViewerContentTransform.current
    val viewport = remember { mutableStateOf(Size.Zero) }
    val frame = remember { mutableStateOf(Size.Zero) }
    val followContent = if (transform == null) Modifier else Modifier.graphicsLayer {
        val edge = viewerVisibleBottomEdge(frame.value, viewport.value, transform.value)
        translationX = edge.translationX
        translationY = edge.translationY
        scaleX = edge.scaleX
    }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport.value = it.toSize() }, contentAlignment = Alignment.Center) {
        Box(Modifier.viewerFittedMedia(aspectRatio).onSizeChanged { frame.value = it.toSize() }, contentAlignment = Alignment.BottomCenter) {
            ViewerPassiveProgressBar(positionMs, durationMs, followContent)
        }
    }
}

fun viewerAspectRatio(width: Int, height: Int): Float? =
    if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null
