package com.theoriacodex.app.viewer

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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
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
    onSeekChanged: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
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

    DisposableEffect(Unit) {
        onDispose {
            onInteractionActiveChanged(false)
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
        onSeekChanged(target)
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
                .defaultMinSize(minHeight = 28.dp)
                .onSizeChanged { sliderWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(safeDuration, sliderWidthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onInteractionActiveChanged(true)
                        var startedScrubbing = false
                        try {
                            if (!isScrubbing) {
                                isScrubbing = true
                                onSeekStarted()
                                startedScrubbing = true
                            }
                            updateScrubTarget(down.position.x)
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                updateScrubTarget(change.position.x)
                                change.consume()
                                if (!change.pressed) break
                            }
                        } finally {
                            if (startedScrubbing || isScrubbing) {
                                val target = scrubPositionMs.coerceIn(0L, safeDuration)
                                onSeekFinished(target)
                            }
                            isScrubbing = false
                            onInteractionActiveChanged(false)
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

private fun formatTimelineTime(valueMs: Long): String {
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
