package com.theoriacodex.app.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaTimelineBar(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    onSeekStarted: () -> Unit = {},
    onSeekFinished: (Long) -> Unit,
    onSeekCancelled: () -> Unit = {},
    preview: @Composable (Long) -> Unit,
    onInteractionActiveChanged: (Boolean) -> Unit = {},
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val clampedPosition = positionMs.coerceIn(0L, safeDuration)
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember(safeDuration) { mutableLongStateOf(clampedPosition) }
    var sliderWidthPx by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(clampedPosition, safeDuration, isScrubbing) {
        if (!isScrubbing) {
            scrubPositionMs = clampedPosition
        }
    }

    val latestStart by rememberUpdatedState(onSeekStarted)
    val latestFinish by rememberUpdatedState(onSeekFinished)
    val latestCancel by rememberUpdatedState(onSeekCancelled)
    val latestInteraction by rememberUpdatedState(onInteractionActiveChanged)

    DisposableEffect(Unit) {
        onDispose {
            if (isScrubbing) { isScrubbing = false; latestCancel() }
            latestInteraction(false)
        }
    }

    val displayPosition = if (isScrubbing) scrubPositionMs else clampedPosition
    val normalizedValue = (displayPosition.toDouble() / safeDuration.toDouble())
        .coerceIn(0.0, 1.0)
        .toFloat()
    val sliderColors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color(0xFF787878),
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
        disabledThumbColor = Color.White,
        disabledActiveTrackColor = Color.White,
        disabledInactiveTrackColor = Color(0xFF787878),
    )

    fun updateScrubTarget(rawOffsetX: Float) {
        val width = sliderWidthPx.coerceAtLeast(1f)
        val normalized = (rawOffsetX / width).coerceIn(0f, 1f).toDouble()
        val target = (normalized * safeDuration.toDouble())
            .roundToLong()
            .coerceIn(0L, safeDuration)
        scrubPositionMs = target
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 40.dp)
                .onSizeChanged { sliderWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .testTag("viewer-timeline-track")
                .pointerInput(safeDuration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var released = false
                        isScrubbing = true
                        latestInteraction(true)
                        latestStart()
                        try {
                            updateScrubTarget(down.position.x)
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.size != 1) break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                updateScrubTarget(change.position.x)
                                change.consume()
                                if (!change.pressed) {
                                    released = true
                                    break
                                }
                            }
                        } finally {
                            if (isScrubbing) {
                                isScrubbing = false
                                if (released) latestFinish(scrubPositionMs.coerceIn(0L, safeDuration))
                                else latestCancel()
                            }
                            latestInteraction(false)
                        }
                    }
                },
        ) {
            Slider(
                value = normalizedValue,
                onValueChange = {},
                enabled = false,
                valueRange = 0f..1f,
                colors = sliderColors,
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(color = Color.White, shape = CircleShape),
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(8.dp),
                        colors = sliderColors,
                        drawStopIndicator = null,
                        thumbTrackGapSize = 0.dp,
                        trackInsideCornerSize = 0.dp,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (isScrubbing) {
                ViewerScrubPreview(scrubPositionMs, normalizedValue) { preview(scrubPositionMs) }
            }
        }
        Text(
            text = "${formatTimelineTime(displayPosition)} / ${formatTimelineTime(safeDuration)}",
            style = MaterialTheme.typography.titleMedium.copy(
                color = Color.White,
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier.widthIn(min = 112.dp),
        )
    }
}

@Composable
fun TimelinePlaybackButton(
    isPaused: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier.size(36.dp),
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
        "%02d:%02d".format(minutes, seconds)
    }
}

/** Display-only progress at the content edge; gestures pass through to the Viewer. */
@Composable
internal fun ViewerPassiveProgressBar(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    if (durationMs <= 0L) return
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        val progress = (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
        drawRect(Color(0xFF787878))
        drawRect(Color.White, size = Size(size.width * progress, size.height))
    }
}
