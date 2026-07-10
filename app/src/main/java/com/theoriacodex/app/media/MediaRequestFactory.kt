package com.theoriacodex.app.media

import android.content.Context
import coil.request.ImageRequest
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.SourceKey

object MediaRequestFactory {
    fun imageRequest(
        context: Context,
        url: String,
        sourceKey: SourceKey,
        crossfade: Boolean,
        allowHardware: Boolean = false,
        staticAnimatedWebPFrame: Boolean = false,
        controllableAnimatedWebP: Boolean = false,
    ): ImageRequest {
        val builder = ImageRequest.Builder(context)
            .data(normalizeMediaUrl(sourceKey, url) ?: url)
            .crossfade(crossfade)
            .allowHardware(allowHardware)
            .animatedWebPDecodeMode(
                when {
                    staticAnimatedWebPFrame -> AnimatedWebPDecodeMode.STATIC_FIRST_FRAME
                    controllableAnimatedWebP -> AnimatedWebPDecodeMode.CONTROLLABLE
                    else -> null
                },
            )
        sourceKey.requestHeaders().forEach { (name, value) ->
            builder.addHeader(name, value)
        }
        return builder.build()
    }
}

internal fun ImageRequest.Builder.staticAnimatedWebPFrame(enabled: Boolean): ImageRequest.Builder {
    return animatedWebPDecodeMode(
        if (enabled) AnimatedWebPDecodeMode.STATIC_FIRST_FRAME else null,
    )
}

internal fun ImageRequest.Builder.animatedWebPDecodeMode(
    mode: AnimatedWebPDecodeMode?,
): ImageRequest.Builder {
    return if (mode != null) {
        setParameter(
            key = ANIMATED_WEBP_DECODE_MODE_PARAMETER,
            value = mode,
            memoryCacheKey = mode.memoryCacheKey,
        )
    } else {
        removeParameter(ANIMATED_WEBP_DECODE_MODE_PARAMETER)
    }
}
