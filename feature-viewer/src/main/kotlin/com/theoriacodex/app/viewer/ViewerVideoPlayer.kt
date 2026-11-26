@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.IntState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.runtime.snapshotFlow
import com.theoriacodex.app.media.isMediaNetworkMetered
import kotlin.math.roundToInt

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
    episode: ViewerEpisodeStreams? = null,
) {
    val context = LocalContext.current
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
    if (!controls.isActive && !(controls.isPreloading && playback.readsAheadFreely())) {
        Box(modifier.background(Color.Black)) { loadingPreview?.invoke() }
        return
    }
    val key = ViewerVideoKey.of(ViewerMediaLoad(mediaKey, controls.loadGeneration), media, streamLabel, playback)
    val recovery = remember(key) { ViewerVideoRecovery(key.load, episode, key.label) }
    val state = remember(key) { ViewerVideoState(recovery.opening(positions.position(mediaKey))) }
    val start = remember(mediaKey, positions) { ViewerVideoStart({ positions.position(mediaKey) }, positions::startsOver) }
    val clipReturn = remember(mediaKey) { ViewerClipReturnPosition() }
    if (state.pending != ViewerRecoveryDecision.AskForFreshLinks || state.player != null) ViewerVideoBinding(state, playback, controls, events, recovery, start) { releasedMs, progress ->
        val resumeMs = clipReturn.ms ?: releasedMs
        positions.save(mediaKey, resumeMs)
        context.videoPlaybackInfrastructure().feedPositionHandoff.publish(mediaKey, resumeMs)
        progress?.let { events.onPlaybackProgress?.invoke(it) }
    }
    ViewerVideoStreamRecovery(state, recovery, episode, events)
    ReportViewerPlaybackProgress(state, events.onPlaybackProgress)
    CountViewerPlayingVideo(state.actuallyPlaying, looping = controls.loopPlayback && state.looped)
    FollowViewerActiveTracks(state.player, controls.isActive, loading = state.loading && !state.failed)
    ViewerVideoSystemBindings(state, controls.isActive && controls.systemControlsReady, controls.fillScreen, events)
    ReportViewerVideoShape(state.player, controls.isActive, mediaKey)
    ViewerVideoCommands(state, controls, clipReturn)
    ViewerVideoLifecycle(state, controls)
    ViewerVideoProgress(state, events)
    ViewerVideoContent(state, postId, playback, playbackDiagnosticsEnabled, controls, events, modifier, mediaModifier, aspectRatio, loadingPreview)
}

internal class ViewerVideoState(pending: ViewerRecoveryDecision?) {
    var player by mutableStateOf<ExoPlayer?>(null)
    var view: PlayerView? = null
    var subtitleView: SubtitleView? = null
    var cues: List<Cue> = emptyList()
    var loading by mutableStateOf(true)
    var hasRenderedFirstFrame by mutableStateOf(false)
    var looped by mutableStateOf(false)
    var pending by mutableStateOf(pending)
    var bindings by mutableIntStateOf(0)
    val failed get() = pending == ViewerRecoveryDecision.Fail
    var started: ViewerStartedPlayback? = null
    var duration by mutableLongStateOf(0L)
    var position by mutableLongStateOf(0L)
    var bufferedPosition by mutableLongStateOf(0L)
    var scrubbing by mutableStateOf(false)
    var actuallyPlaying by mutableStateOf(false)
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
            bufferedPosition = active.bufferedPosition.coerceAtLeast(0L)
                .let { if (duration > 0L) it.coerceAtMost(duration) else it }
        }
    }

    fun seek(target: Long) {
        position = target
        scrubbing = false
        runCatching { player?.seekTo(target) }
    }

    fun progress(): ViewerPlaybackProgress? = started?.progress()

    fun follow(decision: ViewerRecoveryDecision, message: String, events: () -> ViewerRendererEvents) {
        pending = decision
        loading = decision != ViewerRecoveryDecision.Fail
        if (decision == ViewerRecoveryDecision.Fail) events().onError(message)
    }

    fun scrubTo(target: Long) {
        position = target
        runCatching { player?.scrubTo(target) }
    }

    fun release(target: Long, previews: ViewerScrubPreviews?) {
        if (leaveScrubbingMode() || previews == null) return seek(target)
        scrubbing = false
        position = runCatching { previews.seekReleased(target) }.getOrDefault(target)
    }

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
    onRelease: (resumeMs: Long, progress: ViewerPlaybackProgress?) -> Unit,
) {
    val context = LocalContext.current
    val latestEvents by rememberUpdatedState(events)
    val latestOnRelease by rememberUpdatedState(onRelease)
    DisposableEffect(state, playback, state.bindings) {
        val pool = context.videoPlaybackInfrastructure().viewerVideoPlayerPool
        val lease = pool.acquire(playback, loop = controls.repeatsAtEnd)
        val player = lease.player
        val started = start.applyTo(player)
        player.playbackParameters = PlaybackParameters(controls.playbackRate)
        val listener = viewerVideoListener(player, state, recovery) { latestEvents }
        val seeks = ViewerSeekReports(started, { state.scrubbing }) { progress -> latestEvents.onPlaybackProgress?.invoke(progress) }
        player.addListener(listener)
        player.addListener(seeks)
        state.started = started
        state.player = player
        state.setCues(player.currentCues.cues)
        state.setPlaying(controls.isActive && controls.isPlaying)
        onDispose {
            started.resumeMs()?.let { latestOnRelease(it, started.releaseProgress()) }
            player.removeListener(started)
            player.removeListener(seeks)
            player.removeListener(listener)
            state.actuallyPlaying = false
            state.setPlaying(false)
            state.setCues(emptyList())
            if (state.player === player) {
                state.player = null
                state.started = null
            }
            runCatching { state.view?.player = null }
            pool.recycle(lease)
            state.view = null
            state.subtitleView = null
        }
    }
}

@Composable
private fun ViewerVideoSystemBindings(state: ViewerVideoState, active: Boolean, fillScreen: Boolean, events: ViewerRendererEvents) {
    val player = state.player
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latestEvents by rememberUpdatedState(events)
    DisposableEffect(player, active, owner) {
        val binding = if (active && player != null) attachViewerMediaSession(owner.lifecycle) {
            val session = createViewerMediaSession(context, player) { latestEvents.onPlaybackIntentChanged(it) }
            java.io.Closeable { session.release() }
        } else null
        onDispose { binding?.close() }
    }
    DisposableEffect(player, active, fillScreen) {
        val pictureInPicture = if (active && player != null) {
            context.viewerActivity()?.viewerAutoPictureInPicture?.bind(player) { state.view.takeUnless { fillScreen } }
        } else null
        onDispose { pictureInPicture?.close() }
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
            if (state.pending != null) return
            val decision = recovery.decide(ViewerRecoveryEvent.PlayerError(error.errorCode, player.currentPosition.coerceAtLeast(0L)))
            state.follow(decision, viewerVideoFailureMessage(error.errorCode, error.message), events)
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (state.player !== player) return
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            state.looped = true
            events().onPlaybackLooped()
        }
        override fun onRenderedFirstFrame() {
            if (state.player !== player) return
            firstFrame.recordOnce(MediaTraceSections.VIEWER_FIRST_FRAME)
            state.hasRenderedFirstFrame = true
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
        override fun onCues(cueGroup: CueGroup) {
            if (state.player !== player) return
            state.setCues(cueGroup.cues)
        }

    }
}

internal fun ViewerVideoState.setCues(rawCues: List<Cue>) {
    val sanitized = rawCues.map(::sanitizeViewerSubtitleCue)
    cues = sanitized
    subtitleView?.setCues(sanitized)
}

private fun ViewerVideoState.handlePlayingChanged(isPlaying: Boolean, events: () -> ViewerRendererEvents) {
    actuallyPlaying = isPlaying
    if (isPlaying) {
        loading = false
        events().onReady()
    }
}

@Composable
private fun ViewerVideoStreamRecovery(state: ViewerVideoState, recovery: ViewerVideoRecovery, episode: ViewerEpisodeStreams?, events: ViewerRendererEvents) {
    LaunchedEffect(state.hasRenderedFirstFrame) { if (state.hasRenderedFirstFrame) recovery.onPlayed() }
    LaunchedEffect(state) { snapshotFlow { state.position }.collect(recovery::onProgress) }
    val latestEpisode by rememberUpdatedState(episode)
    val latestEvents by rememberUpdatedState(events)
    LaunchedEffect(state, state.pending) {
        when (val pending = state.pending) {
            ViewerRecoveryDecision.RetryInPlace -> {
                delay(250L)
                state.pending = null
                state.bindings += 1
            }
            ViewerRecoveryDecision.AskForFreshLinks -> {
                var outcome: ViewerMediaRecovery? = null
                try {
                    outcome = latestEpisode?.ask?.invoke() ?: ViewerMediaRecovery.NOTHING_NEW
                } finally {
                    if (outcome == null) recovery.onAskCancelled()
                }
                if (outcome == ViewerMediaRecovery.NOTHING_NEW) {
                    state.follow(recovery.decide(ViewerRecoveryEvent.NothingNew), VIEWER_VIDEO_FAILED) { latestEvents }
                }
            }
            is ViewerRecoveryDecision.FallBack -> latestEpisode?.fallBack?.invoke(pending.label)
            ViewerRecoveryDecision.Fail, null -> Unit
        }
    }
}

internal class ViewerVideoStart(
    private val resume: () -> Long,
    private val startsOver: (positionMs: Long, durationMs: Long) -> Boolean,
) {
    private var underWay = false

    fun applyTo(player: Player): ViewerStartedPlayback {
        val startsOver = startsOver.takeUnless { underWay } ?: { _, _ -> false }
        return ViewerStartedPlayback(player, resume().coerceAtLeast(0L), startsOver) { underWay = true }.also(player::addListener)
    }
}

internal class ViewerStartedPlayback(
    private val player: Player,
    private val resumeMs: Long,
    private val startsOver: (positionMs: Long, durationMs: Long) -> Boolean,
    private val arrived: () -> Unit = {},
) : Player.Listener {
    private var settled = resumeMs == 0L
    private var shown = false
    var atPosition = false
        private set

    init {
        if (!settled) {
            player.seekTo(resumeMs)
            settle()
        }
    }

    private fun settle() {
        val durationMs = player.duration.takeIf { it > 0L } ?: return
        if (startsOver(resumeMs, durationMs)) {
            player.seekTo(0L)
            shown = false
        }
        settled = true
        arrive()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (!settled) settle()
    }

    override fun onRenderedFirstFrame() {
        shown = true
        arrive()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) return
        shown = true
        arrive()
    }

    private fun arrive() {
        if (atPosition || !settled || !shown) return
        atPosition = true
        arrived()
    }

    fun progress(positionMs: Long = player.currentPosition): ViewerPlaybackProgress? =
        if (atPosition) viewerPlaybackProgress(positionMs, player.duration) else null

    fun releaseProgress(): ViewerPlaybackProgress? = progress(if (ended) player.duration else player.currentPosition)

    fun resumeMs(): Long? = if (!atPosition) null else if (ended) 0L else player.currentPosition.coerceAtLeast(0L)

    private val ended get() = player.playbackState == Player.STATE_ENDED
}

internal class ViewerSeekReports(
    private val started: ViewerStartedPlayback,
    private val scrubbing: () -> Boolean,
    private val report: (ViewerPlaybackProgress) -> Unit,
) : Player.Listener {
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK || scrubbing()) return
        started.progress(newPosition.positionMs)?.let(report)
    }
}

private fun viewerPlaybackProgress(positionMs: Long, durationMs: Long): ViewerPlaybackProgress? =
    durationMs.takeIf { it > 0L }?.let { ViewerPlaybackProgress(positionMs.coerceIn(0L, it), it) }

@Composable
private fun ReportViewerPlaybackProgress(state: ViewerVideoState, report: ((ViewerPlaybackProgress) -> Unit)?) {
    if (report == null) return
    val latestReport by rememberUpdatedState(report)
    LaunchedEffect(state) {
        reportPlaybackProgress(snapshotFlow { state.actuallyPlaying }, state::progress) { latestReport(it) }
    }
}

@Composable
private fun ViewerVideoCommands(state: ViewerVideoState, controls: ViewerRendererControls, clipReturn: ViewerClipReturnPosition) {
    var handledRestart by remember(state) { mutableLongStateOf(controls.restartRequest) }
    LaunchedEffect(state.player, controls.repeatsAtEnd) {
        state.player?.repeatMode = if (controls.repeatsAtEnd) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
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
    ViewerClipReturn(state, controls.clipping, clipReturn)
    ViewerSeekJumps(controls.seekJumpSerial, controls.seekJumpDeltaMs, { controls.isActive && !state.failed && !state.scrubbing }, state::jump)
}

@Composable
private fun ViewerVideoLifecycle(state: ViewerVideoState, controls: ViewerRendererControls) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, state.player, controls.isActive, controls.isPlaying, state.failed) {
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
    val parentHeightPx = remember { mutableIntStateOf(0) }
    val viewBottomPx = remember { mutableFloatStateOf(0f) }
    val effectiveAspectRatio = if (controls.fillScreen) null else state.aspectRatio ?: aspectRatio
    val videoModifier = if (controls.fillScreen) Modifier.fillMaxSize() else Modifier.viewerFittedMedia(effectiveAspectRatio)
    Box(
        modifier
            .onSizeChanged { parentHeightPx.intValue = it.height }
            .testTag(viewerVideoTestTag(postId))
            .then(diagnostics?.let {
                Modifier.semantics { stateDescription = it.stateDescription; contentDescription = it.contentDescription }
            } ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        ViewerVideoSurface(
            state = state,
            postId = postId,
            diagnosticsEnabled = diagnosticsEnabled,
            fillScreen = controls.fillScreen,
            parentHeightPx = parentHeightPx,
            viewBottomPx = viewBottomPx,
            modifier = videoModifier
                .onPlaced { coordinates -> viewBottomPx.floatValue = coordinates.positionInParent().y + coordinates.size.height }
                .then(mediaModifier),
        )
        if ((!controls.isActive || !state.hasRenderedFirstFrame) && !state.failed) loadingPreview?.invoke()
        val holdScrub = LocalViewerHoldScrub.current
        val holdScrubbing by remember(holdScrub) { derivedStateOf { holdScrub?.phase is ViewerHoldScrubPhase.Dragging } }
        ViewerChromeVisibility(controls.showControls && controls.dimBackdrop && !state.scrubbing && !holdScrubbing, Modifier.matchParentSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = VIEWER_DIM_BACKDROP_ALPHA)))
        }
        if (state.loading && !state.failed) CircularProgressIndicator()
        if (state.failed) Text(VIEWER_VIDEO_FAILED, color = MaterialTheme.colorScheme.onBackground)
        ViewerVideoTimelineOverlay(state, playback, controls, events, effectiveAspectRatio, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ViewerVideoSurface(
    state: ViewerVideoState,
    postId: PostId,
    diagnosticsEnabled: Boolean,
    fillScreen: Boolean,
    parentHeightPx: IntState,
    viewBottomPx: FloatState,
    modifier: Modifier,
) {
    val subtitleBottomLiftPx = with(LocalDensity.current) { LocalViewerSubtitleBottomLift.current.toPx() }
    val subtitleBottomOverlapPx = viewerSubtitleBottomOverlapPx(parentHeightPx.intValue, viewBottomPx.floatValue, subtitleBottomLiftPx)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            createTexturePlayerView(context).apply {
                useController = false
                bindViewerSubtitleOverlay(this, state, subtitleBottomOverlapPx)
                player = state.player
                state.view = this
            }
        },
        update = { view ->
            state.view = view
            view.useController = false
            view.resizeMode = if (fillScreen) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            bindViewerSubtitleOverlay(view, state, subtitleBottomOverlapPx)
            view.player = state.player
            view.tag = if (diagnosticsEnabled) viewerVideoTestTag(postId) else null
        },
    )
}

private fun viewerSubtitleBottomOverlapPx(parentHeightPx: Int, viewBottomPx: Float, liftPx: Float): Int =
    (liftPx - (parentHeightPx - viewBottomPx).coerceAtLeast(0f)).roundToInt().coerceAtLeast(0)

internal fun bindViewerSubtitleOverlay(view: PlayerView, state: ViewerVideoState, bottomOverlapPx: Int): SubtitleView {
    val overlay = view.viewerSubtitleOverlay() ?: view.attachViewerSubtitleView(view.context)
    val reconnected = state.subtitleView !== overlay
    state.subtitleView = overlay
    if (reconnected) overlay.setCues(state.cues)
    overlay.setPadding(0, 0, 0, bottomOverlapPx)
    return overlay
}

private fun PlayerView.viewerSubtitleOverlay(): SubtitleView? {
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        if (child is SubtitleView && child.tag == VIEWER_SUBTITLE_OVERLAY_TAG) return child
    }
    return null
}

private fun PlayerView.attachViewerSubtitleView(context: Context): SubtitleView {
    subtitleView?.visibility = View.GONE
    val overlay = SubtitleView(context).apply {
        tag = VIEWER_SUBTITLE_OVERLAY_TAG
        applyViewerSubtitleStyle()
    }
    addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    return overlay
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
    if (controls.clipping) {
        ViewerVideoClipping(state, controls, events, modifier)
        return
    }
    ViewerChromeVisibility(controls.showControls, modifier) {
        val previews = rememberViewerScrubPreviews(state.player.takeIf { playback.previewsScrubFrames() }, playback)
        ViewerPlaybackTimeline(
            controls, events, { state.position }, state.duration,
            bufferedPositionMs = { state.bufferedPosition },
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

private const val VIEWER_DIM_BACKDROP_ALPHA = 0.34f
private const val VIEWER_SUBTITLE_OVERLAY_TAG = "viewer-subtitle-overlay"
