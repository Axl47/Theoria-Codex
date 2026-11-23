package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import com.theoriacodex.app.media.isMediaNetworkMetered

/** Each mounted renderer leases its player and owns its surface/progress; the route sends commands. */
@Composable
internal fun ViewerVideoPlayer(
    media: ImageRef,
    quality: com.theoriacodex.domain.model.VideoQuality,
    qualityHeight: Int?,
    streamLabel: String?,
    postId: PostId,
    sourceKey: SourceKey,
    playbackDiagnosticsEnabled: Boolean,
    modifier: Modifier = Modifier,
    terminalMessage: String? = null,
    mediaModifier: Modifier = Modifier,
    aspectRatio: Float? = null,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    mediaKey: ViewerMediaKey,
    positions: ViewerVideoPositions,
    loadingPreview: (@Composable () -> Unit)? = null,
    /** Fetches expired stream links again, for media with streams; null for other videos. */
    freshLinks: ViewerFreshLinks? = null,
) {
    val context = LocalContext.current
    // The metered check is a system call, so it runs when the page picks its stream, not on every recomposition.
    val playback = remember(media, sourceKey, quality, qualityHeight, streamLabel) {
        media.viewerPlayback(sourceKey, quality, context.isMediaNetworkMetered(), qualityHeight, streamLabel)
    }
    if (!terminalMessage.isNullOrBlank()) {
        ViewerVideoMessage(terminalMessage, modifier)
        return
    }
    if (playback == null) {
        LaunchedEffect(media, controls.loadGeneration) { events.onError("Video unavailable") }
        ViewerVideoMessage("Video unavailable", modifier)
        return
    }
    // A stream the shared cache does not keep (an episode, any HLS or DASH rendition) does not load ahead of a swipe:
    // nothing would keep what it read for the page to play, and an episode's links expire.
    if (!controls.isActive && !(controls.isPreloading && playback.readsAheadFreely())) {
        Box(modifier.background(Color.Black)) { loadingPreview?.invoke() }
        return
    }
    // New stream links are a new player even when the chosen stream's request reads the same: a REPLACED ask waits on it.
    val recovery = remember(mediaKey, playback, media.streams, controls.loadGeneration, freshLinks?.budget) {
        ViewerVideoRecovery(ViewerMediaLoad(mediaKey, controls.loadGeneration), freshLinks?.budget)
    }
    var recoveryGeneration by remember(recovery) { mutableIntStateOf(0) }
    val state = remember(recovery, recoveryGeneration) { ViewerVideoState() }
    LaunchedEffect(state, state.retryPending) {
        if (state.retryPending) {
            delay(250L)
            recoveryGeneration += 1
        }
    }
    val start = ViewerVideoStart({ positions.position(mediaKey) }, positions::startsOver)
    ViewerVideoBinding(state, playback, controls, events, recovery, start) { positionMs, durationMs, ended ->
        // A finished video resumes from the start: seeking back to its end would finish it again and,
        // in Queue next mode, skip straight past it.
        val resumeMs = if (ended) 0L else positionMs
        positions.save(mediaKey, resumeMs)
        context.videoPlaybackInfrastructure().feedPositionHandoff.publish(mediaKey, resumeMs)
        events.onPlaybackProgress?.let { report -> viewerReleaseProgress(positionMs, durationMs, ended)?.let(report) }
    }
    ViewerVideoFreshLinks(state, recovery, freshLinks, events)
    ReportViewerPlaybackProgress(state, events.onPlaybackProgress)
    FollowViewerActiveTracks(state.player, controls.isActive)
    ViewerVideoSystemBindings(state, controls.isActive && controls.systemControlsReady, events)
    ReportViewerVideoShape(state.player, controls.isActive, mediaKey)
    ViewerVideoCommands(state, controls)
    ViewerVideoLifecycle(state, controls)
    ViewerVideoProgress(state, events)
    ViewerVideoContent(state, postId, playback, playbackDiagnosticsEnabled, controls, events, modifier, mediaModifier, aspectRatio, loadingPreview)
}

private class ViewerVideoState {
    var player by mutableStateOf<ExoPlayer?>(null)
    var view: PlayerView? = null
    var loading by mutableStateOf(true)
    var hasRenderedFirstFrame by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var retryPending by mutableStateOf(false)
    /** Waiting for an expired stream's links to be fetched again; see [ViewerRecoveryDecision.ASK_FOR_FRESH_LINKS]. */
    var recoveringStream by mutableStateOf(false)
    /** Counts the player's seeks, from any controller, so an episode's progress is reported after each. */
    var seeks by mutableIntStateOf(0)
    var duration by mutableLongStateOf(0L)
    var position by mutableLongStateOf(0L)
    var scrubbing by mutableStateOf(false)
    var actuallyPlaying by mutableStateOf(false)
    /** The decoded frames' shape once the player knows it. */
    var aspectRatio by mutableStateOf<Float?>(null)

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
            active.videoSize.let { size -> viewerAspectRatio(size.displayWidth, size.height) }
                ?.let { aspectRatio = it }
            if (!scrubbing) position = active.currentPosition.coerceAtLeast(0L)
                .let { if (duration > 0L) it.coerceAtMost(duration) else it }
        }
    }

    fun seek(target: Long) {
        position = target
        // Not scrubbing any more before the seek, so the player's listener counts it.
        scrubbing = false
        runCatching { player?.seekTo(target) }
    }

    /** Where playback is; null while the duration is unknown. */
    fun progress(): ViewerPlaybackProgress? = viewerPlaybackProgress(position, duration)

    /** Ends in the failure state, which the route hears through [ViewerRendererEvents.onError]. */
    fun fail(message: String, events: () -> ViewerRendererEvents) {
        loading = false
        recoveringStream = false
        failed = true
        events().onError(message)
    }

    /** An instant-seek drag's move to [target] ([ExoPlayer.scrubTo]). */
    fun scrubTo(target: Long) {
        position = target
        runCatching { player?.scrubTo(target) }
    }

    /**
     * A released drag's seek to [target]. After an instant-seek drag the seek is exact and reads on from the keyframe
     * the drag showed; otherwise [previews] may land it on the frame their popup shows.
     */
    fun release(target: Long, previews: ViewerScrubPreviews?) {
        if (leaveScrubbingMode() || previews == null) return seek(target)
        scrubbing = false
        position = runCatching { previews.seekReleased(target) }.getOrDefault(target)
    }

    /** A drag that ended without seeking; an instant-seek drag leaves playback on the last keyframe it showed. */
    fun cancelScrub() {
        leaveScrubbingMode()
        scrubbing = false
    }

    private fun leaveScrubbingMode(): Boolean = runCatching { player?.endScrub() == true }.getOrDefault(false)

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
    playback: VideoPlaybackRequest,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    recovery: ViewerVideoRecovery,
    start: ViewerVideoStart,
    /** Hears where the player stopped as it lets go, how long its video is (unset while unknown), and whether it ended. */
    onRelease: (positionMs: Long, durationMs: Long, ended: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val latestEvents by rememberUpdatedState(events)
    // A stream switch disposes this binding during the composition that replaced the page's state; the latest
    // [onRelease] still saves to this page's position and reports to the latest callback.
    val latestOnRelease by rememberUpdatedState(onRelease)
    DisposableEffect(state, playback) {
        val pool = context.videoPlaybackInfrastructure().viewerVideoPlayerPool
        // The pool can hand back the same player after a stream switch, when the effect that follows
        // [ViewerRendererControls.loopPlayback] would not run again; so the lease starts in the right mode.
        val lease = pool.acquire(playback, loop = controls.loopPlayback)
        val player = lease.player
        // Read after the previous binding's disposal has saved its final position.
        val startCheck = start.applyTo(player)
        player.playbackParameters = PlaybackParameters(controls.playbackRate)
        val listener = viewerVideoListener(player, state, recovery) { latestEvents }
        val seeks = ViewerSeekCounter({ state.scrubbing }) { state.seeks += 1 }
        player.addListener(listener)
        player.addListener(seeks)
        state.player = player
        state.setPlaying(controls.isActive && controls.isPlaying)
        onDispose {
            latestOnRelease(player.currentPosition.coerceAtLeast(0L), player.duration, player.playbackState == Player.STATE_ENDED)
            startCheck?.let(player::removeListener)
            player.removeListener(seeks)
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

/** Only the selected page owns system playback controls; a swipe target merely buffers. */
@Composable
private fun ViewerVideoSystemBindings(state: ViewerVideoState, active: Boolean, events: ViewerRendererEvents) {
    val player = state.player
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latestEvents by rememberUpdatedState(events)
    DisposableEffect(player, active, owner) {
        val binding = if (active && player != null) attachViewerMediaSession(owner.lifecycle) {
            val session = createViewerMediaSession(context, player) { latestEvents.onPlaybackIntentChanged(it) }
            java.io.Closeable { session.release() }
        } else null
        val pictureInPicture = if (active && player != null) {
            context.viewerActivity()?.viewerAutoPictureInPicture?.bind(player) { state.view }
        } else null
        onDispose {
            pictureInPicture?.close()
            binding?.close()
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
            com.theoriacodex.domain.diagnostics.Diagnostics.report("playback", "Viewer video error: ${error.errorCodeName}", error)
            state.handleError(error, recovery, events)
        }
        override fun onRenderedFirstFrame() {
            if (state.player !== player) return
            firstFrame.recordOnce(MediaTraceSections.VIEWER_FIRST_FRAME)
            state.hasRenderedFirstFrame = true
            state.failed = false
            events().onReady()
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                events().onPlaybackIntentChanged(false)
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (state.player !== player) return
            state.handlePlayingChanged(isPlaying, events)
        }

    }
}

private fun ViewerVideoState.handlePlayingChanged(isPlaying: Boolean, events: () -> ViewerRendererEvents) {
    actuallyPlaying = isPlaying
    if (isPlaying) {
        failed = false
        loading = false
        events().onReady()
    }
}

/** Acts on [recovery]'s decision for [error]; the terminal state holds until a new player replaces this one. */
private fun ViewerVideoState.handleError(error: PlaybackException, recovery: ViewerVideoRecovery, events: () -> ViewerRendererEvents) {
    if (retryPending || failed || recoveringStream) return
    when (recovery.decide(error.errorCode)) {
        ViewerRecoveryDecision.RETRY_IN_PLACE -> {
            loading = true
            retryPending = true
        }
        ViewerRecoveryDecision.ASK_FOR_FRESH_LINKS -> {
            loading = true
            recoveringStream = true
        }
        ViewerRecoveryDecision.FAIL -> fail(error.message ?: "Could not play video", events)
    }
}

/**
 * Asks for fresh links when [recovery] decided so, through the latest [freshLinks] (its post may have been replaced
 * since this player started), and gives the ask back once the media plays. Recovered media replaces this player, along
 * with this state and this ask. Nothing new (no links in the route's time, a failure, the same links) fails the stream
 * at once, so another can be picked. Leaving the page cancels the ask.
 */
@Composable
private fun ViewerVideoFreshLinks(state: ViewerVideoState, recovery: ViewerVideoRecovery, freshLinks: ViewerFreshLinks?, events: ViewerRendererEvents) {
    LaunchedEffect(state.hasRenderedFirstFrame) { if (state.hasRenderedFirstFrame) recovery.onPlayed() }
    val latestFreshLinks by rememberUpdatedState(freshLinks)
    val latestEvents by rememberUpdatedState(events)
    LaunchedEffect(state, state.recoveringStream) {
        if (!state.recoveringStream) return@LaunchedEffect
        val outcome = latestFreshLinks?.ask?.invoke() ?: ViewerMediaRecovery.NOTHING_NEW
        if (outcome == ViewerMediaRecovery.NOTHING_NEW) state.fail("Could not play video") { latestEvents }
    }
}

/**
 * Where a page's player starts: the [resume] position, unless, once the duration is known, [startsOver] judges it too
 * close to the end; then it starts from the beginning instead of ending the moment it starts.
 */
private class ViewerVideoStart(
    private val resume: () -> Long,
    private val startsOver: (positionMs: Long, durationMs: Long) -> Boolean,
) {
    /** Seeks [player]; returns the listener still waiting for the duration, to remove on release, or null. */
    fun applyTo(player: Player): Player.Listener? {
        val resumeMs = resume().takeIf { it > 0L } ?: return null
        player.seekTo(resumeMs)
        // The duration is negative (unset) until the timeline is known.
        fun settled(): Boolean {
            val durationMs = player.duration.takeIf { it > 0L } ?: return false
            if (startsOver(resumeMs, durationMs)) player.seekTo(0L)
            return true
        }
        if (settled()) return null
        return object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (settled()) player.removeListener(this)
            }
        }.also(player::addListener)
    }
}

/**
 * Counts a player's seeks, from whichever controller made them (the timeline, a double-tap, the media session), as
 * [counted] calls. A drag's instant seeks while [scrubbing] are one seek, counted when the drag is released.
 */
internal class ViewerSeekCounter(private val scrubbing: () -> Boolean, private val counted: () -> Unit) : Player.Listener {
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK && !scrubbing()) counted()
    }
}

/** What a player that lets go at [positionMs] reports: its end once it [ended]; nothing while [durationMs] is unknown. */
internal fun viewerReleaseProgress(positionMs: Long, durationMs: Long, ended: Boolean): ViewerPlaybackProgress? =
    viewerPlaybackProgress(if (ended) durationMs else positionMs, durationMs)

/** Where playback is, at [positionMs] of [durationMs]; null while the duration is unknown (unset or not positive). */
private fun viewerPlaybackProgress(positionMs: Long, durationMs: Long): ViewerPlaybackProgress? =
    durationMs.takeIf { it > 0L }?.let { ViewerPlaybackProgress(positionMs.coerceIn(0L, it), it) }

/**
 * Whether this video may be read ahead of being shown, preloaded or scrubbed through a second player, without
 * fetching anything twice: a file on the device, or a web file the shared cache keeps for playback.
 */
private fun VideoPlaybackRequest.readsAheadFreely(): Boolean =
    usesSharedCache() || !(location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true))

/**
 * Reports an episode's playback to [report] while it plays, when it stops, and after each seek; the player's release
 * reports once more ([ViewerVideoBinding]). Nothing is reported while the duration is unknown.
 */
@Composable
private fun ReportViewerPlaybackProgress(state: ViewerVideoState, report: ((ViewerPlaybackProgress) -> Unit)?) {
    if (report == null) return
    val latestReport by rememberUpdatedState(report)
    LaunchedEffect(state) {
        launch { snapshotFlow { state.seeks }.drop(1).collect { state.progress()?.let(latestReport) } }
        reportPlaybackProgress(snapshotFlow { state.actuallyPlaying }, state::progress) { latestReport(it) }
    }
}

@Composable
private fun ViewerVideoCommands(state: ViewerVideoState, controls: ViewerRendererControls) {
    var handledRestart by remember(state) { mutableLongStateOf(controls.restartRequest) }
    LaunchedEffect(state.player, controls.loopPlayback) {
        state.player?.repeatMode = if (controls.loopPlayback) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(state.player, controls.playbackRate) {
        runCatching { state.player?.playbackParameters = PlaybackParameters(controls.playbackRate) }
    }
    LaunchedEffect(state.player, controls.restartRequest) {
        if (controls.restartRequest != handledRestart) {
            handledRestart = controls.restartRequest
            state.seek(0L)
            state.setPlaying(controls.isActive && controls.isPlaying)
        }
    }
    ViewerSeekJumps(controls.seekJumpSerial, controls.seekJumpDeltaMs, { controls.isActive && !state.failed && !state.scrubbing }, state::jump)
}

@Composable
private fun ViewerVideoLifecycle(state: ViewerVideoState, controls: ViewerRendererControls) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, state.player, controls.isActive, controls.isPlaying, state.failed) {
        // Applies a command at once; adding the observer replays ON_START/ON_RESUME only while the host is started.
        state.setPlaying(controls.isActive && controls.isPlaying && !state.failed &&
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
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
    playback: VideoPlaybackRequest,
    diagnosticsEnabled: Boolean,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    modifier: Modifier,
    mediaModifier: Modifier,
    aspectRatio: Float?,
    loadingPreview: (@Composable () -> Unit)?,
) {
    val diagnostics = playbackDiagnosticsSemantics(diagnosticsEnabled, state.actuallyPlaying, "Viewer")
    Box(modifier.testTag(viewerVideoTestTag(postId)).then(diagnostics?.let {
        Modifier.semantics { stateDescription = it.stateDescription; contentDescription = it.contentDescription }
    } ?: Modifier), contentAlignment = Alignment.Center) {
        // The decoded frame's shape wins over post metadata, which can disagree with the stream and would
        // letterbox the video inside its own bounds (and inside the PiP window, which takes those bounds).
        AndroidView(
            modifier = Modifier.viewerFittedMedia(state.aspectRatio ?: aspectRatio).then(mediaModifier),
            factory = { context -> createTexturePlayerView(context).apply { useController = false; player = state.player; state.view = this } },
            update = { view ->
                state.view = view
                view.useController = false
                view.player = state.player
                view.tag = if (diagnosticsEnabled) viewerVideoTestTag(postId) else null
            },
        )
        if ((!controls.isActive || !state.hasRenderedFirstFrame) && !state.failed) loadingPreview?.invoke()
        if (state.loading && !state.failed) CircularProgressIndicator()
        if (state.failed) Text("Could not play video", color = MaterialTheme.colorScheme.onBackground)
        ViewerVideoTimelineOverlay(state, playback, controls, events, state.aspectRatio ?: aspectRatio, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ViewerVideoTimelineOverlay(
    state: ViewerVideoState, playback: VideoPlaybackRequest, controls: ViewerRendererControls,
    events: ViewerRendererEvents, aspectRatio: Float?, modifier: Modifier,
) {
    if (!controls.isActive && !controls.isPreloading || state.failed) return
    if (state.duration <= 0L || state.player == null) {
        ViewerPendingTimeline(controls, events, modifier)
        return
    }
    ViewerChromeVisibility(controls.showControls, modifier) {
        // Inside the visibility, so scrub previews are prepared only while the timeline shows. They read through the
        // shared cache, so a release plays from bytes they fetched; a stream it does not keep would fetch every frame twice.
        val previews = rememberViewerScrubPreviews(state.player.takeIf { playback.readsAheadFreely() }, playback)
        ViewerPlaybackTimeline(
            controls, events, { state.position }, state.duration,
            seek = ViewerTimelineSeek(
                onStarted = { state.scrubbing = true },
                onMoved = state::scrubTo,
                onFinished = { target -> state.release(target, previews) },
                onCancelled = state::cancelScrub,
                preview = { target -> previews?.let { ViewerVideoScrubPreview(it, target) } },
            ),
            onToggled = { state.setPlaying(!controls.isPlaying && controls.isActive) },
        )
    }
    if (controls.showPassiveProgress) ViewerMediaPassiveProgress({ state.position }, state.duration, aspectRatio)
}

@Composable
private fun ViewerVideoMessage(message: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { Text(message, color = MaterialTheme.colorScheme.onBackground) }
}

internal fun viewerVideoTestTag(postId: PostId): String = "viewer_video_${postId.mediaTestTagPart()}"
