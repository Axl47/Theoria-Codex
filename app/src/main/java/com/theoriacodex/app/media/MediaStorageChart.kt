package com.theoriacodex.app.media

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

@Composable
internal fun MediaStorageChart(breakdown: MediaStorageBreakdown) {
    val context = LocalContext.current
    val numbers = NumberFormat.getIntegerInstance()
    val percentages = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
    val colors = mapOf(
        MediaStorageCategory.OFFLINE to Color(0xFF7AA2F7),
        MediaStorageCategory.IMAGES to Color(0xFF9ECE6A),
        MediaStorageCategory.VIDEO to Color(0xFFBB9AF7),
        MediaStorageCategory.GIF to Color(0xFFE0AF68),
        MediaStorageCategory.ANIMATION to Color(0xFF7DCFFF),
    )
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val total = Formatter.formatShortFileSize(context, breakdown.totalBytes)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Media storage", style = MaterialTheme.typography.titleMedium)
        Text(
            "$total total (${numbers.format(breakdown.totalBytes)} bytes)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Canvas(
            modifier = Modifier
                .size(176.dp)
                .align(Alignment.CenterHorizontally)
                .testTag("media-storage-chart")
                .semantics { contentDescription = "Media storage chart. $total total. Breakdown listed below." },
        ) {
            if (breakdown.totalBytes == 0L) {
                drawCircle(emptyColor)
            } else {
                var startAngle = -90f
                breakdown.categories.forEach { usage ->
                    val sweep = (breakdown.percentage(usage) * 3.6).toFloat()
                    if (sweep > 0f) {
                        drawArc(colors.getValue(usage.category), startAngle, sweep, useCenter = true)
                        startAngle += sweep
                    }
                }
            }
        }
        if (breakdown.totalBytes == 0L) {
            Text("No managed media stored yet.", style = MaterialTheme.typography.bodyMedium)
        }
        breakdown.categories.forEach { usage ->
            val percentage = breakdown.percentage(usage)
            val percentageLabel = if (percentage > 0.0 && percentage < 0.1) {
                "<${percentages.format(0.001)}"
            } else {
                percentages.format(percentage / 100.0)
            }
            Row(
                modifier = Modifier.fillMaxWidth().testTag("storage-category:${usage.category}"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).background(colors.getValue(usage.category), CircleShape))
                Column(modifier = Modifier.weight(1f)) {
                    Text(usage.category.label(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${Formatter.formatShortFileSize(context, usage.bytes)} (${numbers.format(usage.bytes)} bytes)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(percentageLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun MediaStorageCategory.label(): String = when (this) {
    MediaStorageCategory.OFFLINE -> "Offline copies"
    MediaStorageCategory.IMAGES -> "Image cache"
    MediaStorageCategory.VIDEO -> "Video cache"
    MediaStorageCategory.GIF -> "GIF cache"
    MediaStorageCategory.ANIMATION -> "Animation cache"
}
