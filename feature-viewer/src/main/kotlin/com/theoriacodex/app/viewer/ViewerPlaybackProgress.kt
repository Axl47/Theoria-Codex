package com.theoriacodex.app.viewer

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Reports an episode's playback, read by [read] (null while its duration is unknown), to [report]: every
 * [VIEWER_PROGRESS_INTERVAL_MS] while [playing] holds, and once each time it stops, so a killed process resumes close
 * to where it was. Runs until cancelled.
 */
internal suspend fun reportPlaybackProgress(
    playing: Flow<Boolean>,
    read: () -> ViewerPlaybackProgress?,
    report: (ViewerPlaybackProgress) -> Unit,
) {
    playing.distinctUntilChanged().collectLatest { isPlaying ->
        if (!isPlaying) {
            read()?.let(report)
            return@collectLatest
        }
        while (true) {
            delay(VIEWER_PROGRESS_INTERVAL_MS)
            read()?.let(report)
        }
    }
}

internal const val VIEWER_PROGRESS_INTERVAL_MS = 10_000L
