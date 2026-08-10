package com.theoriacodex.app.viewer

import android.content.Context
import coil.imageLoader
import coil.request.SuccessResult
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.ViewerMediaState
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import com.theoriacodex.domain.model.ImageRef
import java.io.File

/** Android transport/cache seam injected into the route owner; no platform handle enters state. */
internal suspend fun prefetchViewerMedia(
    context: Context,
    media: ViewerMediaState,
): ViewerPrefetchResult {
    if (media.kind == ViewerMediaKind.VIDEO) {
        return prefetchViewerVideoMedia(context, media.ref, media.key.postId.source.requestHeaders())
    }

    val location = media.displayLocation
        ?: media.ref.localPath
        ?: media.ref.url
        ?: return ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED)
    val request = MediaRequestFactory.imageRequest(
        context = context,
        url = location,
        sourceKey = media.key.postId.source,
        crossfade = false,
    )
    return if (context.imageLoader.execute(request) is SuccessResult) {
        ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED)
    } else {
        ViewerPrefetchResult(ViewerPrefetchOutcome.FAILED)
    }
}

private suspend fun prefetchViewerVideoMedia(
    context: Context,
    media: ImageRef,
    headers: Map<String, String>,
): ViewerPrefetchResult {
    val location = media.localPath?.takeIf(String::isNotBlank)
        ?: media.url?.takeIf(String::isNotBlank)
        ?: return ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED)
    if (location.startsWith("http://", true) || location.startsWith("https://", true)) {
        return context.videoPlaybackInfrastructure().prefetch(location, headers)
    }
    if (location.startsWith("content://", true)) {
        return ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED)
    }
    val bytes = File(location).takeIf(File::isFile)?.length()?.takeIf { it > 0L }
        ?: return ViewerPrefetchResult(ViewerPrefetchOutcome.FAILED)
    return ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED, bytesCached = bytes)
}

internal fun resolveViewerVideoPlaybackLocation(media: ImageRef): String? {
    return media.localPath?.takeIf(String::isNotBlank)
        ?: media.url?.takeIf(String::isNotBlank)
}
