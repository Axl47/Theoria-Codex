package com.theoriacodex.app.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

@Composable
fun MediaTimelineBar(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    onSeekStarted: () -> Unit = {},
    onSeekChanged: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val clampedPosition = positionMs.coerceIn(0L, safeDuration)
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember(safeDuration) { mutableLongStateOf(clampedPosition) }

    LaunchedEffect(clampedPosition, safeDuration, isScrubbing) {
        if (!isScrubbing) {
            scrubPositionMs = clampedPosition
        }
    }

    val displayPosition = if (isScrubbing) scrubPositionMs else clampedPosition

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatTimelineTime(displayPosition),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = formatTimelineTime(safeDuration),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Slider(
                value = displayPosition.toFloat(),
                onValueChange = { next ->
                    if (!isScrubbing) {
                        isScrubbing = true
                        onSeekStarted()
                    }
                    val target = next.roundToLong().coerceIn(0L, safeDuration)
                    scrubPositionMs = target
                    onSeekChanged(target)
                },
                onValueChangeFinished = {
                    val target = scrubPositionMs.coerceIn(0L, safeDuration)
                    onSeekFinished(target)
                    isScrubbing = false
                },
                valueRange = 0f..safeDuration.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
