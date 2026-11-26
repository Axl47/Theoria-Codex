package com.theoriacodex.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

@Composable
internal fun ViewerVideoClipping(state: ViewerVideoState, controls: ViewerRendererControls, events: ViewerRendererEvents, modifier: Modifier) {
    val clip = controls.clip
    LaunchedEffect(clip == null, state.duration) {
        if (clip == null && state.duration > 0L) initialEpisodeClip(state.position, state.duration)?.let(events.onClipRangeChanged)
    }
    if (clip == null) return
    var shown by remember { mutableStateOf<EpisodeClipRange?>(null) }
    LaunchedEffect(state.player, clip) {
        val previous = shown
        shown = clip
        state.seek(if (previous != null && previous != clip && previous.startMs == clip.startMs) (clip.endMs - CLIP_END_PREVIEW_MS).coerceAtLeast(clip.startMs) else clip.startMs)
        while (true) {
            delay(CLIP_LOOP_POLL_MS)
            val at = state.player?.currentPosition ?: continue
            if (at >= clip.endMs || at < clip.startMs - CLIP_LOOP_SLACK_MS) state.seek(clip.startMs)
        }
    }
    ViewerChromeVisibility(controls.showControls, modifier) {
        ViewerTimelineVeil {
            ViewerPlaybackFooter { ViewerClipStrip({ state.position }, state.duration, clip, events.onClipRangeChanged) }
        }
    }
}

internal class ViewerClipReturnPosition {
    var ms: Long? = null
}

@Composable
internal fun ViewerClipReturn(state: ViewerVideoState, clipping: Boolean, returnTo: ViewerClipReturnPosition) {
    val current by rememberUpdatedState(state)
    LaunchedEffect(clipping) {
        if (clipping) {
            if (returnTo.ms == null) returnTo.ms = current.position
        } else {
            returnTo.ms?.let(current::seek)
            returnTo.ms = null
        }
    }
}

private const val CLIP_LOOP_POLL_MS = 50L
private const val CLIP_LOOP_SLACK_MS = 500L
private const val CLIP_END_PREVIEW_MS = 2_000L
