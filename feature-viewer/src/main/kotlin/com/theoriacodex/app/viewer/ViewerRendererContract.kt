package com.theoriacodex.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity

/** Bind asynchronous renderer events to the producing session, media, and retry generation. */
internal fun viewerRendererEvents(
    session: ViewerSessionIdentity?,
    key: ViewerMediaKey,
    generation: Long,
    onAction: (ViewerAction) -> Unit,
    onTimelineInteractionActiveChanged: (Boolean) -> Unit,
    onDurationKnown: (Long) -> Unit,
): ViewerRendererEvents = ViewerRendererEvents(
    onPlaybackCompleted = { session?.let { onAction(ViewerAction.PlaybackCompleted(it, key, generation)) } },
    onError = { message -> session?.let {
        onAction(ViewerAction.MediaFailed(it, ViewerMediaError.Recoverable(key, message), generation))
    } },
    onReady = { session?.let { onAction(ViewerAction.MediaReady(it, key, generation)) } },
    onDurationKnown = onDurationKnown,
    onTogglePlayback = { onAction(ViewerAction.TogglePlayback) },
    onRestartPlayback = { onAction(ViewerAction.RestartPlayback) },
    onPlaybackIntentChanged = { onAction(if (it) ViewerAction.Play else ViewerAction.Pause) },
    onTimelineInteractionActiveChanged = onTimelineInteractionActiveChanged,
)

/**
 * Runs [play] while [playing] and the host is at least started and [pause] otherwise, following its lifecycle;
 * [pause] also runs when [key] or [playing] changes and on disposal. For self-animating drawables (the GIF fallback,
 * animated WebP), keyed by the drawable.
 */
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
