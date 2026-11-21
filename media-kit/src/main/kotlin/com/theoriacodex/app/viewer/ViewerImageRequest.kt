package com.theoriacodex.app.viewer

import android.content.Context
import coil.request.ImageRequest
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.domain.model.SourceKey

/**
 * No crossfade: the preview leaves as the image arrives, so fading in would dip through the background.
 * Decodes to hardware bitmaps so a full-size page is not uploaded to the GPU by RenderThread as it settles;
 * callers that read pixels (the artwork accent) opt back into software with `allowHardware(false)`.
 */
fun buildViewerImageRequest(
    context: Context,
    url: String,
    sourceKey: SourceKey,
    staticAnimatedWebPFrame: Boolean = false,
    targetWidthPx: Int? = null,
    targetHeightPx: Int? = null,
): ImageRequest = MediaRequestFactory.imageRequest(
    context = context,
    url = url,
    sourceKey = sourceKey,
    crossfade = false,
    allowHardware = true,
    staticAnimatedWebPFrame = staticAnimatedWebPFrame,
    targetWidthPx = targetWidthPx,
    targetHeightPx = targetHeightPx,
)
