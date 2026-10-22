package com.theoriacodex.app.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post

/** Media implementations receive the same commands and identity-bound events. */
@Composable
internal fun ViewerMediaContent(
    post: Post,
    media: ImageRef,
    key: ViewerMediaKey,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    videoOptions: ViewerVideoOptions,
    videoPositions: ViewerVideoPositions,
    imageSession: ViewerImageSession,
    pixivUgoiraClient: PixivUgoiraClient?,
    transformModifier: Modifier,
    onRequestMediaRecovery: ((Post, ImageRef) -> Unit)?,
) {
    when {
        isPixivUgoiraMedia(post, media) && pixivUgoiraClient != null -> Box(
            Modifier.fillMaxSize(),
        ) {
            PixivUgoiraPlayer(
                postId = post.id.sourcePostId,
                client = pixivUgoiraClient,
                contentDescription = post.title ?: post.id.sourcePostId,
                modifier = Modifier.fillMaxSize(),
                loopPlayback = controls.loopPlayback,
                onPlaybackCompleted = events.onPlaybackCompleted,
                showProgressBar = controls.showControls,
                showPassiveProgress = controls.showPassiveProgress,
                mediaModifier = transformModifier,
                isActive = controls.isActive,
                isPlaying = controls.isPlaying,
                seekJumpSerial = controls.seekJumpSerial,
                seekJumpDeltaMs = controls.seekJumpDeltaMs,
                playbackRate = controls.playbackRate,
                restartRequest = controls.restartRequest,
                loadGeneration = controls.loadGeneration,
                onTimelineInteractionActiveChanged = events.onTimelineInteractionActiveChanged,
                onTogglePlayback = events.onTogglePlayback,
                onDurationKnown = events.onDurationKnown,
                onError = events.onError,
                loadingPreview = { ViewerLoadingPreview(post, media) },
            )
        }
        isVideoMediaRef(media) -> ViewerVideoPlayer(
            media = media, postId = post.id, sourceKey = post.id.source,
            quality = videoOptions.quality, qualityHeight = videoOptions.height,
            playbackDiagnosticsEnabled = videoOptions.diagnosticsEnabled,
            terminalMessage = videoOptions.terminalMessage,
            modifier = Modifier.fillMaxSize(), mediaModifier = transformModifier,
            controls = controls, events = events,
            mediaKey = key, positions = videoPositions,
            loadingPreview = { ViewerLoadingPreview(post, media, transformModifier) },
        )
        isGifMediaRef(media) -> {
            val locations = remember(post, media, controls.loadGeneration) { viewerGifLocations(post, media) }
            var ready by remember(key, controls.loadGeneration) { mutableStateOf(false) }
            if (!ready) ViewerLoadingPreview(post, media, transformModifier)
            ViewerGifPlayer(
                sourceKey = post.id.source, locations = locations,
                modifier = Modifier.fillMaxSize(), mediaModifier = transformModifier,
                controls = controls, events = events.copy(onReady = { ready = true; events.onReady() }),
            )
        }
        else -> ViewerImageRenderer(
            post = post, media = media, key = key,
            modifier = Modifier.fillMaxSize(), mediaModifier = transformModifier,
            controls = controls, events = events, imageSession = imageSession,
            onRequestMediaRecovery = onRequestMediaRecovery,
        )
    }
}
