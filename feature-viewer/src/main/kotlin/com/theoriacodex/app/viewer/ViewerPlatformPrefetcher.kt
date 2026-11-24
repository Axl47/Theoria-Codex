package com.theoriacodex.app.viewer

import android.content.Context
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.media.isMediaNetworkMetered
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.ViewerMediaState
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

fun viewerMediaPrefetcher(
    context: Context,
    ugoiraClient: PixivUgoiraClient? = null,
    videoQuality: () -> VideoQuality,
) = ViewerMediaPrefetcher { _, media, request ->
    prefetchViewerMedia(
        context, media, videoQuality(),
        keepDecodedInMemory = request.distance <= VIEWER_MEMORY_PREFETCH_DISTANCE,
        ugoiraClient = ugoiraClient,
        pageWidthPx = request.pageWidthPx,
    )
}

suspend fun prefetchViewerMedia(
    context: Context,
    media: ViewerMediaState,
    videoQuality: VideoQuality,
    keepDecodedInMemory: Boolean = true,
    ugoiraClient: PixivUgoiraClient? = null,
    pageWidthPx: Int? = null,
): ViewerPrefetchResult {
    if (media.kind == ViewerMediaKind.UGOIRA && ugoiraClient != null && !context.isMediaNetworkMetered()) {
        val ready = ugoiraClient.warmArchive(media.key.postId.sourcePostId)
        return ViewerPrefetchResult(if (ready) ViewerPrefetchOutcome.WARMED else ViewerPrefetchOutcome.FAILED)
    }
    if (media.kind == ViewerMediaKind.VIDEO) {
        val playback = media.ref.viewerPlayback(media.key.postId.source, videoQuality, context.isMediaNetworkMetered())
        return prefetchViewerVideoMedia(context, playback, maxBytes = VIEWER_VIDEO_PREFETCH_BYTES)
    }

    val location = media.displayLocation
        ?: media.ref.localPath
        ?: media.ref.url
        ?: return ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED)
    if (media.kind == ViewerMediaKind.GIF && location.startsWith("http", ignoreCase = true)) {
        val bytes = warmRemoteViewerGif(context, media.key.postId.source, location)
        return ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED, bytes)
    }
    if (media.kind == ViewerMediaKind.IMAGE) {
        viewerPrefetchThumbnailLocation(media.ref, location)?.let { thumbnail ->
            context.imageLoader.execute(
                MediaRequestFactory.imageRequest(context, thumbnail, media.key.postId.source, crossfade = false).diskWarmOnly(),
            )
        }
    }
    val request = viewerPrefetchImageRequest(context, media, location, keepDecodedInMemory, pageWidthPx)
    return if (context.imageLoader.execute(request) is SuccessResult) {
        ViewerPrefetchResult(
            ViewerPrefetchOutcome.WARMED,
            decoded = keepDecodedInMemory && media.kind == ViewerMediaKind.IMAGE,
        )
    } else {
        ViewerPrefetchResult(ViewerPrefetchOutcome.FAILED)
    }
}

private fun viewerPrefetchImageRequest(
    context: Context, media: ViewerMediaState, location: String, keepDecodedInMemory: Boolean, pageWidthPx: Int?,
): ImageRequest {
    val source = media.key.postId.source
    val request = if (media.kind == ViewerMediaKind.IMAGE && pageWidthPx != null) {
        buildViewerPageRequest(context, location, source, pageWidthPx)
    } else {
        buildViewerImageRequest(context, location, source)
    }
    return when {
        keepDecodedInMemory -> request
        media.kind == ViewerMediaKind.IMAGE -> request.diskWarmOnly()
        else -> request.newBuilder().memoryCachePolicy(CachePolicy.READ_ONLY).build()
    }
}

private const val DISK_WARM_DECODE_PX = 64

private const val PAGE_THUMBNAIL_WARM_PARALLELISM = 8

private fun ImageRequest.diskWarmOnly() = newBuilder()
    .memoryCachePolicy(CachePolicy.READ_ONLY)
    .size(DISK_WARM_DECODE_PX)
    .precision(Precision.INEXACT)
    .build()

internal suspend fun warmViewerPageThumbnails(context: Context, source: SourceKey, items: List<ViewerMediaOverviewItem>, fromPage: Int) {
    val urls = items
        .filter { it.thumbnailLocation != null && it.thumbnailLocation != it.posterLocation }
        .sortedBy { abs(it.mediaIndex - fromPage) }
        .mapNotNull { it.thumbnailLocation }
    val slots = Semaphore(PAGE_THUMBNAIL_WARM_PARALLELISM)
    coroutineScope {
        for (url in urls) {
            launch {
                slots.withPermit {
                    context.imageLoader.execute(MediaRequestFactory.imageRequest(context, url, source, crossfade = false).diskWarmOnly())
                }
            }
        }
    }
}

private suspend fun prefetchViewerVideoMedia(
    context: Context,
    playback: VideoPlaybackRequest?,
    maxBytes: Long,
): ViewerPrefetchResult {
    if (playback == null) return ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED)
    if (playback.usesSharedCache()) return context.videoPlaybackInfrastructure().prefetch(playback, maxBytes)
    val location = playback.location
    if (!playback.readsAheadFreely() || !playback.cache || location.startsWith("content://", true)) {
        return ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED)
    }
    val bytes = File(location).takeIf(File::isFile)?.length()?.takeIf { it > 0L }
        ?: return ViewerPrefetchResult(ViewerPrefetchOutcome.FAILED)
    return ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED, bytesCached = bytes)
}
