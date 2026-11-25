package com.theoriacodex.app.viewer

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity

internal fun viewerRendererEvents(
    session: ViewerSessionIdentity?,
    key: ViewerMediaKey,
    generation: Long,
    onAction: (ViewerAction) -> Unit,
    onTimelineInteractionActiveChanged: (Boolean) -> Unit,
    onDurationKnown: (Long) -> Unit,
    onPlaybackProgress: ((ViewerPlaybackProgress) -> Unit)? = null,
    nowMs: () -> Long = SystemClock::uptimeMillis,
): ViewerRendererEvents = ViewerRendererEvents(
    onPlaybackCompleted = { session?.let { onAction(ViewerAction.PlaybackCompleted(it, key, generation, nowMs())) } },
    onPlaybackLooped = { session?.let { onAction(ViewerAction.PlaybackCompleted(it, key, generation, nowMs(), looped = true)) } },
    onError = { message -> session?.let {
        onAction(ViewerAction.MediaFailed(it, ViewerMediaError.Recoverable(key, message), generation))
    } },
    onReady = { session?.let { onAction(ViewerAction.MediaReady(it, key, generation)) } },
    onDurationKnown = onDurationKnown,
    onTogglePlayback = { onAction(ViewerAction.TogglePlayback) },
    onRestartPlayback = { onAction(ViewerAction.RestartPlayback) },
    onPlaybackIntentChanged = { onAction(if (it) ViewerAction.Play else ViewerAction.Pause) },
    onTimelineInteractionActiveChanged = onTimelineInteractionActiveChanged,
    onPlaybackProgress = onPlaybackProgress,
)

@Composable
internal fun ViewerPlayWhileStarted(key: Any?, playing: Boolean, play: () -> Unit, pause: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(key, owner, playing) {
        fun sync() = if (playing && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) play() else pause()
        val observer = LifecycleEventObserver { _, _ -> sync() }
        owner.lifecycle.addObserver(observer)
        sync()
        onDispose { owner.lifecycle.removeObserver(observer); pause() }
    }
}
