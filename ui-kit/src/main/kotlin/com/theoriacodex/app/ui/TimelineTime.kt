package com.theoriacodex.app.ui

import java.util.Locale

/** A playback time as the Viewer's timeline writes it: "8:05", or "1:02:05" past an hour. Negative reads as 0:00. */
fun formatTimelineTime(valueMs: Long): String {
    val totalSeconds = (valueMs.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(Locale.getDefault(), hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.getDefault(), minutes, seconds)
    }
}
