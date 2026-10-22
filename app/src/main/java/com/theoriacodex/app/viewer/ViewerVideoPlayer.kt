package com.theoriacodex.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import kotlinx.coroutines.delay
import com.theoriacodex.app.media.isMediaNetworkMetered
import com.theoriacodex.app.media.withVideoQuality

/** Each mounted renderer leases its player and owns its surface/progress; the route sends commands. */
@Composable
internal fun ViewerVideoPlayer(
    media: ImageRef,
    quality: com.theoriacodex.domain.model.VideoQuality,
    qualityHeight: Int?,
    postId: PostId,
    sourceKey: SourceKey,
    playbackDiagnosticsEnabled: Boolean,
    modifier: Modifier = Modifier,
    terminalMessage: String? = null,
    mediaModifier: Modifier = Modifier,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    mediaKey: ViewerMediaKey,
    positions: ViewerVideoPositions,
    loadingPreview: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val metered = context.isMediaNetworkMetered()
    val location = remember(media, quality, qualityHeight, metered) {
        resolveViewerVideoPlaybackLocation(media.withVideoQuality(quality, metered, qualityHeight))
    }
    if (!terminalMessage.isNullOrBlank()) {
        ViewerVideoMessage(terminalMessage, modifier)
        return
    }
    if (location.isNullOrBlank()) {
        LaunchedEffect(media, controls.loadGeneration) { events.onError("Video unavailable") }
        ViewerVideoMessage("Video unavailable", modifier)
        return
    }
    if (!controls.isActive) {
        Box(modifier.background(Color.Black)) {}
        return
    }
    val recovery = remember(mediaKey, location, sourceKey, controls.loadGeneration) { ViewerVideoRecovery() }
    var recoveryGeneration by remember(recovery) { mutableIntStateOf(0) }
    val state = remember(recovery, recoveryGeneration) { ViewerVideoState() }
    LaunchedEffect(state, state.retryPending) {
        if (state.retryPending) {
            delay(250L)
            recoveryGeneration += 1
        }
    }
    ViewerVideoBinding(state, location, sourceKey, controls, events, recovery, { positions.position(mediaKey) }) {
        positions.save(mediaKey, it)
    }
    ViewerVideoCommands(state, controls)
    ViewerVideoLifecycle(state, controls)
    ViewerVideoProgress(state, events)
    ViewerVideoContent(state, postId, playbackDiagnosticsEnabled, controls, events, modifier, mediaModifier, loadingPreview)
}

private class ViewerVideoState {
    var player by mutableStateOf<ExoPlayer?>(null)
    var view: PlayerView? = null
    var loading by mutableStateOf(true)
    var hasRenderedFirstFrame by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var retryPending by mutableStateOf(false)
    var duration by mutableLongStateOf(0L)
    var position by mutableLongStateOf(0L)
    var scrubbing by mutableStateOf(false)
    var actuallyPlaying by mutableStateOf(false)

    fun setPlaying(playing: Boolean) {
        runCatching {
            player?.playWhenReady = playing
            if (playing && !failed) player?.play() else player?.pause()
        }
    }

    fun updateProgress() {
        val active = player ?: return
        runCatching {
            active.duration.takeIf { it > 0L }?.let { duration = it }
            if (!scrubbing) position = active.currentPosition.coerceAtLeast(0L)
                .let { if (duration > 0L) it.coerceAtMost(duration) else it }
        }
    }

    fun seek(target: Long) {
        position = target
        runCatching { player?.seekTo(target) }
        scrubbing = false
    }

    fun jump(deltaMs: Long) {
        val active = player ?: return
        runCatching {
            val total = active.duration.takeIf { it > 0L } ?: duration
            val target = (active.currentPosition + deltaMs).coerceAtLeast(0L)
                .let { if (total > 0L) it.coerceAtMost(total) else it }
            seek(target)
        }
    }
}

@Composable
private fun ViewerVideoBinding(
    state: ViewerVideoState,
    location: String,
    source: SourceKey,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    recovery: ViewerVideoRecovery,
    resumePosition: () -> Long,
    onReleasePosition: (Long) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestEvents by rememberUpdatedState(events)
    DisposableEffect(state, location, source) {
        val pool = context.videoPlaybackInfrastructure().viewerVideoPlayerPool
        val lease = pool.acquire(location, source.requestHeaders())
        val player = lease.resource
        val binding = attachViewerMediaSession(lifecycleOwner.lifecycle) {
            val session = createViewerMediaSession(context, player) { latestEvents.onPlaybackIntentChanged(it) }
            java.io.Closeable { session.release() }
        }
        // Read after the previous binding's disposal has saved its final position.
        resumePosition().takeIf { it > 0L }?.let(player::seekTo)
        player.playbackParameters = PlaybackParameters(controls.playbackRate.coerceAtLeast(MIN_VIEWER_PLAYBACK_RATE))
        val listener = viewerVideoListener(player, state, recovery) { latestEvents }
        player.addListener(listener)
        state.player = player
        state.setPlaying(controls.isActive && controls.isPlaying)
        val pictureInPicture = context.viewerActivity()?.viewerAutoPictureInPicture?.bind(player)
        onDispose {
            pictureInPicture?.close()
            binding.close()
            onReleasePosition(player.currentPosition.coerceAtLeast(0L))
            player.removeListener(listener)
            state.actuallyPlaying = false
            state.setPlaying(false)
            if (state.player === player) state.player = null
            runCatching { state.view?.player = null }
            pool.recycle(lease)
            state.view = null
        }
    }
}

private fun viewerVideoListener(
    player: ExoPlayer,
    state: ViewerVideoState,
    recovery: ViewerVideoRecovery,
    events: () -> ViewerRendererEvents,
): Player.Listener {
    val firstFrame = FirstFrameTraceGate()
    return object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (state.player !== player) return
            runCatching {
                state.loading = playbackState == Player.STATE_IDLE || playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) events().onPlaybackCompleted()
                player.duration.takeIf { it > 0L }?.let { state.duration = it }
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            if (state.player !== player) return
            if (state.retryPending || state.failed) return
            if (recovery.requestRetry(error.errorCode)) {
                state.loading = true
                state.retryPending = true
                return
            }
            state.loading = false
            state.failed = true
            events().onError(error.message ?: "Could not play video")
        }
        override fun onRenderedFirstFrame() {
            if (state.player !== player) return
            firstFrame.recordOnce(MediaTraceSections.VIEWER_FIRST_FRAME)
            state.hasRenderedFirstFrame = true
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                events().onPlaybackIntentChanged(false)
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { state.actuallyPlaying = isPlaying }
    }
}

@Composable
private fun ViewerVideoCommands(state: ViewerVideoState, controls: ViewerRendererControls) {
    var handledRestart by remember(state) { mutableLongStateOf(controls.restartRequest) }
    LaunchedEffect(state.player, controls.loopPlayback) {
        state.player?.repeatMode = if (controls.loopPlayback) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(state.player, controls.playbackRate) {
        runCatching { state.player?.playbackParameters = PlaybackParameters(controls.playbackRate.coerceAtLeast(MIN_VIEWER_PLAYBACK_RATE)) }
    }
    LaunchedEffect(state.player, controls.restartRequest) {
        if (controls.restartRequest != handledRestart) {
            handledRestart = controls.restartRequest
            state.seek(0L)
            state.setPlaying(controls.isActive && controls.isPlaying)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.player, controls.isActive, controls.isPlaying, state.failed) {
        state.setPlaying(controls.isActive && controls.isPlaying && !state.failed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    LaunchedEffect(state.player, controls.seekJumpSerial, controls.seekJumpDeltaMs, controls.isActive, state.failed, state.duration) {
        if (controls.seekJumpSerial > 0 && controls.seekJumpDeltaMs != 0L && controls.isActive && !state.failed && !state.scrubbing) {
            state.jump(controls.seekJumpDeltaMs)
        }
    }
}

@Composable
private fun ViewerVideoLifecycle(state: ViewerVideoState, controls: ViewerRendererControls) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, state.player, controls.isActive, controls.isPlaying, state.failed) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> state.setPlaying(controls.isActive && controls.isPlaying && !state.failed)
                Lifecycle.Event.ON_PAUSE -> if (context.viewerActivity()?.keepViewerPlayingOnPause != true) state.setPlaying(false)
                Lifecycle.Event.ON_STOP -> state.setPlaying(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun ViewerVideoProgress(state: ViewerVideoState, events: ViewerRendererEvents) {
    LaunchedEffect(state, state.duration) { if (state.duration > 0L) events.onDurationKnown(state.duration) }
    LaunchedEffect(state, state.player, state.failed, state.scrubbing) {
        if (state.failed) return@LaunchedEffect
        while (true) {
            delay(120L)
            state.updateProgress()
        }
    }
}

@Composable
private fun ViewerVideoContent(
    state: ViewerVideoState,
    postId: PostId,
    diagnosticsEnabled: Boolean,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    modifier: Modifier,
    mediaModifier: Modifier,
    loadingPreview: (@Composable () -> Unit)?,
) {
    val diagnostics = playbackDiagnosticsSemantics(diagnosticsEnabled, state.actuallyPlaying, "Viewer")
    Box(modifier.testTag(viewerVideoTestTag(postId)).then(diagnostics?.let {
        Modifier.semantics { stateDescription = it.stateDescription; contentDescription = it.contentDescription }
    } ?: Modifier), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize().then(mediaModifier),
            factory = { context -> createTexturePlayerView(context).apply { useController = false; player = state.player; state.view = this } },
            update = { view ->
                state.view = view
                view.useController = false
                view.player = state.player
                view.tag = if (diagnosticsEnabled) viewerVideoTestTag(postId) else null
            },
        )
        if (!state.hasRenderedFirstFrame && !state.failed) loadingPreview?.invoke()
        if (state.loading && !state.failed) CircularProgressIndicator()
        if (state.failed) Text("Could not play video", color = MaterialTheme.colorScheme.onBackground)
        ViewerVideoTimelineOverlay(state, postId.source, controls, events, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ViewerVideoTimelineOverlay(
    state: ViewerVideoState, source: SourceKey, controls: ViewerRendererControls,
    events: ViewerRendererEvents, modifier: Modifier,
) {
    if (!controls.isActive || state.failed || state.duration <= 0L || state.player == null) return
    if (controls.showControls) ViewerVideoTimeline(state, source, controls, events, modifier)
    else if (controls.showPassiveProgress) ViewerPassiveProgressBar(state.position, state.duration, modifier)
}

@Composable
private fun ViewerVideoTimeline(
    state: ViewerVideoState,
    source: SourceKey,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    modifier: Modifier,
) {
    ViewerPlaybackFooter(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TimelinePlaybackButton(isPaused = !controls.isPlaying, onToggle = {
                events.onTogglePlayback()
                state.setPlaying(!controls.isPlaying && controls.isActive)
                events.onTimelineInteractionActiveChanged(true)
                events.onTimelineInteractionActiveChanged(false)
            })
            MediaTimelineBar(
                positionMs = state.position, durationMs = state.duration,
                onSeekStarted = { state.scrubbing = true },
                onSeekFinished = state::seek,
                onSeekCancelled = { state.scrubbing = false },
                preview = { target ->
                    state.player?.currentMediaItem?.localConfiguration?.uri?.toString()?.let { location ->
                        ViewerVideoScrubPreview(location, source, target)
                    }
                },
                onInteractionActiveChanged = events.onTimelineInteractionActiveChanged, modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ViewerVideoMessage(message: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { Text(message, color = MaterialTheme.colorScheme.onBackground) }
}

internal fun viewerVideoTestTag(postId: PostId): String = "viewer_video_${postId.mediaTestTagPart()}"
