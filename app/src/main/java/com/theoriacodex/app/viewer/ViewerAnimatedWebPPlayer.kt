package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.drawable.ScaleDrawable
import com.github.penfeizhou.animation.webp.WebPDrawable
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.delay
import coil.request.onAnimationEnd
import kotlinx.coroutines.launch

@Composable
internal fun ViewerAnimatedWebPPlayer(
    sourceKey: SourceKey,
    location: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    mediaModifier: Modifier = Modifier,
    onLoading: () -> Unit,
    onSuccess: () -> Unit,
    onError: (Throwable) -> Unit,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
) {
    val context = LocalContext.current
    val state = remember(location, controls.loadGeneration) { ViewerWebPState() }
    val request = remember(context, location, sourceKey, controls.loadGeneration) {
        MediaRequestFactory.imageRequest(context, location, sourceKey, crossfade = false, controllableAnimatedWebP = true)
    }
    ViewerWebPCompletion(state.drawable, controls.loopPlayback, events.onPlaybackCompleted)
    ViewerWebPLifecycle(state.drawable, controls)
    ViewerWebPProgress(state, controls)
    Box(modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request, contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize().then(mediaModifier), contentScale = ContentScale.Fit,
            onLoading = { onLoading() },
            onSuccess = {
                state.drawable = it.result.drawable.unwrapWebPDrawable()
                state.frameCount = state.drawable?.frameSeqDecoder?.frameCount ?: 0
                onSuccess()
            },
            onError = { onError(it.result.throwable) },
        )
        ViewerChromeVisibility(controls.showControls && state.drawable != null, Modifier.align(Alignment.BottomCenter)) {
            ViewerTimelineVeil { ViewerWebPFooter(state, controls, events) }
        }
        if (controls.showPassiveProgress && state.frameCount > 1) {
            ViewerPassiveProgressBar({ state.frameIndex.toLong() }, state.frameCount - 1L, Modifier.align(Alignment.BottomCenter))
        }
    }
}

private class ViewerWebPState {
    var drawable by mutableStateOf<WebPDrawable?>(null)
    var frameIndex by mutableIntStateOf(0)
    var frameCount by mutableIntStateOf(0)
}

@Composable
private fun ViewerWebPCompletion(drawable: WebPDrawable?, loop: Boolean, onCompleted: () -> Unit) {
    val latestCompletion by rememberUpdatedState(onCompleted)
    val scope = rememberCoroutineScope()
    DisposableEffect(drawable, loop) {
        drawable?.setLoopLimit(if (loop) 0 else 1)
        val callback = object : androidx.vectordrawable.graphics.drawable.Animatable2Compat.AnimationCallback() {
            override fun onAnimationEnd(drawable: android.graphics.drawable.Drawable) {
                if (!loop) scope.launch { latestCompletion() }
            }
        }
        drawable?.registerAnimationCallback(callback)
        onDispose { drawable?.unregisterAnimationCallback(callback) }
    }
}

@Composable
private fun ViewerWebPLifecycle(drawable: WebPDrawable?, controls: ViewerRendererControls) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(drawable, owner, controls.isActive, controls.isPlaying) {
        fun sync() {
            if (drawable == null) return
            if (controls.isActive && controls.isPlaying && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (drawable.isPaused) drawable.resume() else drawable.start()
            } else drawable.pause()
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        owner.lifecycle.addObserver(observer)
        sync()
        onDispose { owner.lifecycle.removeObserver(observer); drawable?.pause() }
    }
}

@Composable
private fun ViewerWebPProgress(state: ViewerWebPState, controls: ViewerRendererControls) {
    LaunchedEffect(state.drawable, controls.isActive) {
        val drawable = state.drawable ?: return@LaunchedEffect
        state.frameCount = drawable.frameSeqDecoder.frameCount
        while (controls.isActive) {
            state.frameIndex = drawable.frameSeqDecoder.frameIndex.coerceAtLeast(0)
            delay(100L)
        }
    }
    LaunchedEffect(controls.restartRequest, state.drawable) {
        if (controls.restartRequest > 0L) { state.drawable?.reset(); state.frameIndex = 0 }
    }
}

/** Playback buttons, the frame counter and a track, laid out like the other timelines; see [viewerTimelineOnOneLine]. */
@Composable
private fun ViewerWebPFooter(state: ViewerWebPState, controls: ViewerRendererControls, events: ViewerRendererEvents) {
    val buttons: @Composable () -> Unit = {
        TimelinePlaybackButton(isPaused = !controls.isPlaying, onToggle = events.onTogglePlayback)
        IconButton(modifier = Modifier.size(VIEWER_TIMELINE_ROW_HEIGHT), onClick = {
            events.onRestartPlayback()
            state.drawable?.reset()
            if (controls.isActive) state.drawable?.start()
            state.frameIndex = 0
        }) { Icon(Icons.Default.Replay, contentDescription = "Restart animation") }
        Text(
            text = if (state.frameCount > 0) "${state.frameIndex.coerceIn(0, state.frameCount - 1) + 1}/${state.frameCount}" else "—",
            modifier = Modifier.padding(start = 4.dp),
            style = ViewerVeil.Time,
        )
    }
    val track: @Composable (Modifier) -> Unit = { modifier ->
        ViewerTimelineTrack(
            fraction = { if (state.frameCount <= 1) 0f else state.frameIndex.coerceIn(0, state.frameCount - 1).toFloat() / (state.frameCount - 1) },
            modifier = modifier.height(VIEWER_TIMELINE_TRACK_HEIGHT),
            showHandle = false,
        )
    }
    ViewerPlaybackFooter {
        if (viewerTimelineOnOneLine()) {
            Row(Modifier.fillMaxWidth().height(VIEWER_TIMELINE_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                buttons()
                track(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp))
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(VIEWER_TIMELINE_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) { buttons() }
                track(Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            }
        }
    }
}

private tailrec fun android.graphics.drawable.Drawable.unwrapWebPDrawable(): WebPDrawable? {
    return when (this) {
        is WebPDrawable -> this
        is ScaleDrawable -> child.unwrapWebPDrawable()
        else -> null
    }
}
