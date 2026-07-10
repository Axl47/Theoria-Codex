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
    ): ImageRequest {
        val builder = ImageRequest.Builder(context)
            .data(normalizeMediaUrl(sourceKey, url) ?: url)
            .crossfade(crossfade)
            .allowHardware(allowHardware)
            .staticAnimatedWebPFrame(staticAnimatedWebPFrame)
        sourceKey.requestHeaders().forEach { (name, value) ->
            builder.addHeader(name, value)
        }
        return builder.build()
    }
}

internal fun ImageRequest.Builder.staticAnimatedWebPFrame(enabled: Boolean): ImageRequest.Builder {
    return if (enabled) {
        setParameter(
            key = ANIMATED_WEBP_DECODE_MODE_PARAMETER,
            value = AnimatedWebPDecodeMode.STATIC_FIRST_FRAME,
            memoryCacheKey = STATIC_ANIMATED_WEBP_MEMORY_CACHE_KEY,
        )
    } else {
        removeParameter(ANIMATED_WEBP_DECODE_MODE_PARAMETER)
    }
}
