package com.theoriacodex.app.search

import com.theoriacodex.domain.model.SortMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.ANIMATED_DURATION_MAX_BUCKET
import com.theoriacodex.app.media.ANIMATED_DURATION_MIN_BUCKET
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.animatedDurationBucketLabel
import com.theoriacodex.app.media.animatedDurationRangeLabel
import kotlin.math.roundToInt

@Composable
fun AnimatedDurationRangeControl(
    range: AnimatedDurationRange,
    onRangeChange: (AnimatedDurationRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Animated duration", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (range.isFullRange) "Any" else animatedDurationRangeLabel(range),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RangeSlider(
            value = range.normalizedMinBucket.toFloat()..range.normalizedMaxBucket.toFloat(),
            onValueChange = { values ->
                val minBucket = values.start.roundToInt()
                    .coerceIn(ANIMATED_DURATION_MIN_BUCKET, ANIMATED_DURATION_MAX_BUCKET)
                val maxBucket = values.endInclusive.roundToInt()
                    .coerceIn(ANIMATED_DURATION_MIN_BUCKET, ANIMATED_DURATION_MAX_BUCKET)
                onRangeChange(AnimatedDurationRange(minOf(minBucket, maxBucket), maxOf(minBucket, maxBucket)))
            },
            valueRange = ANIMATED_DURATION_MIN_BUCKET.toFloat()..ANIMATED_DURATION_MAX_BUCKET.toFloat(),
            steps = (ANIMATED_DURATION_MAX_BUCKET - ANIMATED_DURATION_MIN_BUCKET - 1).coerceAtLeast(0),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DurationEndpointLabel(ANIMATED_DURATION_MIN_BUCKET)
            DurationEndpointLabel(ANIMATED_DURATION_MAX_BUCKET)
        }
    }
}

@Composable
private fun DurationEndpointLabel(bucket: Int) {
    Text(
        text = animatedDurationBucketLabel(bucket),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

fun sortModeLabel(sortMode: SortMode): String = when (sortMode) {
    SortMode.NEWEST -> "Newest"
    SortMode.POPULAR -> "Popular"
    SortMode.TOP -> "Top"
    SortMode.RANDOM -> "Random"
}
