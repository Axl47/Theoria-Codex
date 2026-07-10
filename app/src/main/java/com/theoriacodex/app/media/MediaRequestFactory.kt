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
    ): ImageRequest {
        val builder = ImageRequest.Builder(context)
            .data(normalizeMediaUrl(sourceKey, url) ?: url)
            .crossfade(crossfade)
            .allowHardware(allowHardware)
        sourceKey.requestHeaders().forEach { (name, value) ->
            builder.addHeader(name, value)
        }
        return builder.build()
    }
}
