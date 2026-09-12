package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.VideoQuality
import com.theoriacodex.domain.model.VideoVariant

/** Select a rendition without changing gallery identity or canonical download metadata. */
fun ImageRef.withVideoQuality(quality: VideoQuality, metered: Boolean, height: Int? = null): ImageRef {
    if (!localPath.isNullOrBlank() || videoVariants.isEmpty()) return this
    val variants = videoVariants.filter { it.url.isNotBlank() }.distinctBy(VideoVariant::url)
    val selected = if (height != null) {
        variants.firstOrNull { it.height == height }
    } else if (quality == VideoQuality.BEST || quality == VideoQuality.AUTO && !metered) {
        variants.maxWithOrNull(compareBy<VideoVariant> { it.original }.thenBy { it.height ?: 0 })
    } else {
        val cap = if (quality == VideoQuality.DATA_SAVER) 480 else 720
        val sized = variants.filter { it.height != null }
        sized.filter { requireNotNull(it.height) <= cap }.maxByOrNull { requireNotNull(it.height) }
            ?: sized.minByOrNull { requireNotNull(it.height) }
            ?: variants.firstOrNull()
    }
    return selected?.let { copy(url = it.url) } ?: this
}
