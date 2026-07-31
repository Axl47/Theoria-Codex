@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.search

import android.content.Context
import android.graphics.drawable.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.viewer.FirstFrameTraceGate
import com.theoriacodex.app.viewer.MediaTraceSections
import com.theoriacodex.app.viewer.VideoPlaybackProfile
import com.theoriacodex.app.viewer.createLoopingExoPlayer
import com.theoriacodex.app.viewer.createTexturePlayerView
import com.theoriacodex.app.viewer.playbackDiagnosticsSemantics
import com.theoriacodex.app.viewer.traceMediaSection
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

@Composable
internal fun SearchVideoPreview(
    media: ImageRef,
    postId: PostId,
    sourceKey: SourceKey,
    playbackDiagnosticsEnabled: Boolean,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    previewModel: Any? = null,
    onPlaybackError: () -> Unit = {},
) {
    val context = LocalContext.current
    val location = media.localPath ?: media.url
    if (location.isNullOrBlank()) {
        onPlaybackError()
        return
    }

    val activation = remember(location, sourceKey) { FeedPlayerActivationState() }
    var shouldOwnPlayer by remember(location, sourceKey) { mutableStateOf(false) }
    LaunchedEffect(activation, isActive) {
        if (activation.update(isActive).shouldPrepare) {
            shouldOwnPlayer = true
        }
    }
    if (!shouldOwnPlayer) {
        UnpreparedSearchVideoPreview(
            postId = postId,
            previewModel = previewModel,
            modifier = modifier,
        )
        return
    }

    val playerState = rememberFeedPreviewPlayerState(
        location = location,
        sourceKey = sourceKey,
        isActive = isActive,
        onPlaybackError = onPlaybackError,
    )
    PreparedSearchVideoPreview(
        state = playerState,
        postId = postId,
        previewModel = previewModel,
        playbackDiagnosticsEnabled = playbackDiagnosticsEnabled,
        isActive = isActive,
        modifier = modifier,
    )
}

@Composable
private fun UnpreparedSearchVideoPreview(
    postId: PostId,
    previewModel: Any?,
    modifier: Modifier,
) {
    Box(modifier = modifier.testTag(searchVideoTestTag(postId))) {
        if (previewModel != null) {
            FeedAsyncImage(
                model = previewModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                isActive = false,
            )
        }
    }
}

@Composable
private fun rememberFeedPreviewPlayerState(
    location: String,
    sourceKey: SourceKey,
    isActive: Boolean,
    onPlaybackError: () -> Unit,
): FeedPreviewPlayerState {
    val context = LocalContext.current
    val state = remember(location, sourceKey) { FeedPreviewPlayerState() }
    val latestOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val latestIsActive by rememberUpdatedState(isActive)

    DisposableEffect(location, sourceKey) {
        state.reset()
        val player = traceMediaSection(MediaTraceSections.PREVIEW_PREPARE) {
            createLoopingExoPlayer(
                context = context,
                location = location,
                headers = sourceKey.requestHeaders(),
                muted = true,
                profile = VideoPlaybackProfile.FEED_PREVIEW,
            )
        }
        val listener = feedPreviewPlayerListener(
            state = state,
            player = player,
            isActive = { latestIsActive },
            onPlaybackError = { latestOnPlaybackError() },
        )
        player.addListener(listener)
        state.player = player
        onDispose {
            releaseFeedPreviewPlayer(state, player, listener)
        }
    }

    LaunchedEffect(state.player, isActive) {
        val player = state.player ?: return@LaunchedEffect
        runCatching {
            player.playWhenReady = isActive
            if (isActive) player.play() else player.pause()
        }
    }
    return state
}

private fun feedPreviewPlayerListener(
    state: FeedPreviewPlayerState,
    player: ExoPlayer,
    isActive: () -> Boolean,
    onPlaybackError: () -> Unit,
): Player.Listener {
    val firstFrameTraceGate = FirstFrameTraceGate()
    return object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (state.player !== player || playbackState != Player.STATE_READY || !isActive()) return
            runCatching {
                player.playWhenReady = true
                player.play()
            }
        }

        override fun onRenderedFirstFrame() {
            if (state.hasRenderedFirstFrame) return
            firstFrameTraceGate.recordOnce(MediaTraceSections.PREVIEW_FIRST_FRAME)
            state.hasRenderedFirstFrame = true
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            state.isActuallyPlaying = isPlaying
        }

        override fun onPlayerError(error: PlaybackException) {
            if (state.didNotifyError) return
            state.didNotifyError = true
            onPlaybackError()
        }
    }
}

private fun releaseFeedPreviewPlayer(
    state: FeedPreviewPlayerState,
    player: ExoPlayer,
    listener: Player.Listener,
) {
    player.removeListener(listener)
    runCatching {
        player.playWhenReady = false
        player.pause()
    }
    runCatching { state.playerView?.player = null }
    runCatching { player.release() }
    if (state.player === player) state.player = null
    state.isActuallyPlaying = false
    state.playerView = null
}

@Composable
private fun PreparedSearchVideoPreview(
    state: FeedPreviewPlayerState,
    postId: PostId,
    previewModel: Any?,
    playbackDiagnosticsEnabled: Boolean,
    isActive: Boolean,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .testTag(searchVideoTestTag(postId))
            .then(
                playbackDiagnosticsSemantics(
                    enabled = playbackDiagnosticsEnabled,
                    isPlaying = state.isActuallyPlaying,
                    surface = "Search",
                )?.let { diagnostics ->
                    Modifier.semantics {
                        stateDescription = diagnostics.stateDescription
                        contentDescription = diagnostics.contentDescription
                    }
                } ?: Modifier,
            ),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { factoryContext ->
                createTexturePlayerView(factoryContext).apply {
                    player = state.player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    state.playerView = this
                    isClickable = false
                    isFocusable = false
                }
            },
            update = { playerView ->
                state.playerView = playerView
                playerView.useController = false
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                playerView.player = state.player
                playerView.isClickable = false
                playerView.isFocusable = false
            },
        )
        if (!state.hasRenderedFirstFrame && previewModel != null) {
            FeedAsyncImage(
                model = previewModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                isActive = isActive,
            )
        }
    }
}

private class FeedPreviewPlayerState {
    var player by mutableStateOf<ExoPlayer?>(null)
    var playerView: PlayerView? = null
    var didNotifyError = false
    var hasRenderedFirstFrame by mutableStateOf(false)
    var isActuallyPlaying by mutableStateOf(false)

    fun reset() {
        didNotifyError = false
        hasRenderedFirstFrame = false
        isActuallyPlaying = false
    }
}

internal fun buildFeedImageRequest(
    context: Context,
    url: String,
    sourceKey: SourceKey,
    decodeSize: FeedPreviewDecodeSize,
): ImageRequest {
    return MediaRequestFactory.imageRequest(
        context = context,
        url = url,
        sourceKey = sourceKey,
        crossfade = false,
        targetWidthPx = decodeSize.widthPx,
        targetHeightPx = decodeSize.heightPx,
    )
}

internal fun isVisibleFeedBounds(bounds: Rect): Boolean {
    return hasVisibleFeedArea(bounds.width, bounds.height)
}

@Composable
internal fun FeedAsyncImage(
    model: Any?,
    contentDescription: String?,
    contentScale: ContentScale,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null,
) {
    var animatable by remember(model) { mutableStateOf<Animatable?>(null) }
    DisposableEffect(animatable, isActive) {
        if (isActive) animatable?.start() else animatable?.stop()
        onDispose { animatable?.stop() }
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        onSuccess = { state -> animatable = state.result.drawable as? Animatable },
        onError = { state ->
            animatable = null
            onError?.invoke(state)
        },
    )
}
