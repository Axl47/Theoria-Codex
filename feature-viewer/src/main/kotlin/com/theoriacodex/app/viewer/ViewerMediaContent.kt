package com.theoriacodex.app.viewer

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.viewerMediaKind
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post

/** Media implementations receive the same commands and identity-bound events. */
@Composable
internal fun ViewerMediaContent(
    post: Post,
    media: ImageRef,
    key: ViewerMediaKey,
    kind: ViewerMediaKind,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    videoOptions: ViewerVideoOptions,
    videoPositions: ViewerVideoPositions,
    imageSession: ViewerImageSession,
    pixivUgoiraClient: PixivUgoiraClient?,
    modifier: Modifier,
    onRequestMediaRecovery: ViewerMediaRecoveryRequest?,
) {
    when (kind) {
        ViewerMediaKind.UGOIRA -> if (pixivUgoiraClient != null) PixivUgoiraPlayer(
            postId = post.id.sourcePostId, client = pixivUgoiraClient, controls = controls, events = events,
            contentDescription = post.title ?: post.id.sourcePostId,
            modifier = Modifier.fillMaxSize(), mediaModifier = modifier,
            loadingPreview = { ViewerLoadingPreview(post, media) },
        )
        ViewerMediaKind.VIDEO -> ViewerVideoPlayer(
            media = media, postId = post.id, sourceKey = post.id.source,
            quality = videoOptions.quality, qualityHeight = videoOptions.height, streamLabel = videoOptions.streamLabel,
            playbackDiagnosticsEnabled = videoOptions.diagnosticsEnabled,
            terminalMessage = videoOptions.terminalMessage,
            modifier = Modifier.fillMaxSize(), mediaModifier = modifier,
            aspectRatio = viewerMediaAspectRatio(post, media),
            controls = controls, events = events,
            mediaKey = key, positions = videoPositions,
            loadingPreview = { ViewerLoadingPreview(post, media, modifier) },
            freshLinks = videoOptions.freshLinks?.takeIf { media.streams.isNotEmpty() && onRequestMediaRecovery != null }?.let { budget ->
                ViewerFreshLinks(budget) { onRequestMediaRecovery?.invoke(post, media) ?: ViewerMediaRecovery.NOTHING_NEW }
            },
        )
        ViewerMediaKind.GIF -> {
            val locations = remember(post, media, controls.loadGeneration) { viewerGifLocations(post, media) }
            var ready by remember(key, controls.loadGeneration) { mutableStateOf(false) }
            if (!ready) ViewerLoadingPreview(post, media, modifier)
            ViewerGifPlayer(
                sourceKey = post.id.source, locations = locations,
                modifier = Modifier.fillMaxSize(), mediaModifier = modifier,
                controls = controls, events = events.copy(onReady = { ready = true; events.onReady() }),
            )
        }
        else -> ViewerImageRenderer(
            post = post, media = media, key = key, animated = kind.animated,
            modifier = Modifier.fillMaxSize(), mediaModifier = modifier,
            controls = controls, events = events, imageSession = imageSession,
            onRequestMediaRecovery = onRequestMediaRecovery,
        )
    }
}

/**
 * The kind of renderer [media] gets: [viewerMediaKind], except that an ugoira without a client to play it
 * shows its still image.
 */
internal fun viewerRendererMediaKind(post: Post, media: ImageRef, hasUgoiraClient: Boolean): ViewerMediaKind =
    viewerMediaKind(post, media).takeUnless { it == ViewerMediaKind.UGOIRA && !hasUgoiraClient } ?: ViewerMediaKind.IMAGE
