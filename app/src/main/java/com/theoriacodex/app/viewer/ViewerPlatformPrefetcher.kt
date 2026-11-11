package com.theoriacodex.app.viewer

import android.content.Context
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.media.isMediaNetworkMetered
import com.theoriacodex.app.media.viewerPlaybackLocation
import com.theoriacodex.app.source.requestHeaders
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

/** The Viewer route's prefetcher: how far from the current media a prefetch may stay decoded is decided here. */
internal fun viewerMediaPrefetcher(context: Context, videoQuality: () -> VideoQuality) =
    ViewerMediaPrefetcher { _, media, distance ->
        prefetchViewerMedia(context, media, videoQuality(), keepDecodedInMemory = distance <= VIEWER_MEMORY_PREFETCH_DISTANCE)
    }

/**
 * Android transport/cache seam injected into the route owner; no platform handle enters state.
 * [keepDecodedInMemory] keeps a prefetched image in Coil's memory cache; otherwise only its disk copy is warmed.
 */
internal suspend fun prefetchViewerMedia(
    context: Context,
    media: ViewerMediaState,
    videoQuality: VideoQuality,
    keepDecodedInMemory: Boolean = true,
): ViewerPrefetchResult {
    if (media.kind == ViewerMediaKind.VIDEO) {
        val metered = context.isMediaNetworkMetered()
        return prefetchViewerVideoMedia(
            context,
            media.ref.viewerPlaybackLocation(videoQuality, metered),
            media.key.postId.source.requestHeaders(),
            maxBytes = VIEWER_VIDEO_PREFETCH_BYTES,
        )
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
        // The page's small thumbnail lands first: it is what the page shows while its original downloads.
        viewerPrefetchThumbnailLocation(media.ref, location)?.let { thumbnail ->
            context.imageLoader.execute(
                MediaRequestFactory.imageRequest(context, thumbnail, media.key.postId.source, crossfade = false).diskWarmOnly(),
            )
        }
    }
    val request = MediaRequestFactory.imageRequest(
        context = context,
        url = location,
        sourceKey = media.key.postId.source,
        crossfade = false,
    ).let { request ->
        when {
            keepDecodedInMemory -> request
            // A far neighbour may reuse an existing memory entry but never adds one. Only its download matters,
            // so a static image decodes subsampled instead of as a full-size bitmap that is thrown away.
            media.kind == ViewerMediaKind.IMAGE -> request.diskWarmOnly()
            else -> request.newBuilder().memoryCachePolicy(CachePolicy.READ_ONLY).build()
        }
    }
    return if (context.imageLoader.execute(request) is SuccessResult) {
        ViewerPrefetchResult(
            ViewerPrefetchOutcome.WARMED,
            decoded = keepDecodedInMemory && media.kind == ViewerMediaKind.IMAGE,
        )
    } else {
        ViewerPrefetchResult(ViewerPrefetchOutcome.FAILED)
    }
}

/** A disk-only image warm decodes to about this edge; the decode is discarded. */
private const val DISK_WARM_DECODE_PX = 64

/** Pages of one post warmed at the same time. */
private const val PAGE_THUMBNAIL_WARM_PARALLELISM = 8

/** Reuses an existing memory entry but never adds one, and decodes subsampled: only the download matters. */
private fun ImageRequest.diskWarmOnly() = newBuilder()
    .memoryCachePolicy(CachePolicy.READ_ONLY)
    .size(DISK_WARM_DECODE_PX)
    .precision(Precision.INEXACT)
    .build()

/**
 * Warms the disk cache with the dedicated page thumbnails of one post, pages nearest [fromPage] first.
 * Pages whose thumbnail is just their poster are skipped, since posters can be heavy. Failed loads are ignored.
 */
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
    playbackLocation: String?,
    headers: Map<String, String>,
    maxBytes: Long,
): ViewerPrefetchResult {
    val location = playbackLocation ?: return ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED)
    if (location.startsWith("http://", true) || location.startsWith("https://", true)) {
        return context.videoPlaybackInfrastructure().prefetch(location, headers, maxBytes)
    }
    if (location.startsWith("content://", true)) {
        return ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED)
    }
    val bytes = File(location).takeIf(File::isFile)?.length()?.takeIf { it > 0L }
        ?: return ViewerPrefetchResult(ViewerPrefetchOutcome.FAILED)
    return ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED, bytesCached = bytes)
}
