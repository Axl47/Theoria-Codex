package com.theoriacodex.app.media

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.SourceKey
import java.io.File

object MediaRequestFactory {
    fun imageRequest(
        context: Context,
        url: String,
        sourceKey: SourceKey,
        crossfade: Boolean,
        allowHardware: Boolean = false,
        staticAnimatedWebPFrame: Boolean = false,
        controllableAnimatedWebP: Boolean = false,
        targetWidthPx: Int? = null,
        targetHeightPx: Int? = null,
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
        if (targetWidthPx != null && targetHeightPx != null) {
            builder
                .size(targetWidthPx.coerceAtLeast(1), targetHeightPx.coerceAtLeast(1))
                .precision(Precision.INEXACT)
        }
        sourceKey.requestHeaders().forEach { (name, value) ->
            builder.addHeader(name, value)
        }
        return builder.build()
    }
}

/**
 * Copies Coil's raw disk-cache entry for [url] to [destination] without decoding or networking. Entries are
 * keyed by the normalized URL that [MediaRequestFactory.imageRequest] loads. Never writes Coil metadata.
 * Returns false when no entry of 1..[maxBytes] bytes is cached.
 */
@OptIn(ExperimentalCoilApi::class)
internal fun copyCachedMediaBytes(
    context: Context,
    sourceKey: SourceKey,
    url: String,
    destination: File,
    maxBytes: Long = Long.MAX_VALUE,
): Boolean {
    val key = normalizeMediaUrl(sourceKey, url) ?: url
    return context.imageLoader.diskCache?.openSnapshot(key)?.use { snapshot ->
        val cached = snapshot.data.toFile()
        if (cached.length() !in 1..maxBytes) return@use false
        cached.copyTo(destination, overwrite = true)
        true
    } ?: false
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
