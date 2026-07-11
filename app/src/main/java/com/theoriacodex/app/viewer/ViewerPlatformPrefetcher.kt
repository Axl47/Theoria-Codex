package com.theoriacodex.app.viewer

import android.content.Context
import coil.imageLoader
import coil.request.SuccessResult
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.ViewerMediaState

/** Android transport/cache seam injected into the route owner; no platform handle enters state. */
internal suspend fun prefetchViewerMedia(
    context: Context,
    media: ViewerMediaState,
): Boolean {
    if (media.kind == ViewerMediaKind.VIDEO) {
        return prefetchViewerVideoMediaBounded(
            context = context,
            media = media.ref,
            headers = media.key.postId.source.requestHeaders(),
        )
    }

    val location = media.displayLocation
        ?: media.ref.localPath
        ?: media.ref.url
        ?: return false
    val request = MediaRequestFactory.imageRequest(
        context = context,
        url = location,
        sourceKey = media.key.postId.source,
        crossfade = false,
    )
    return context.imageLoader.execute(request) is SuccessResult
}
