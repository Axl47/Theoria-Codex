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
    return selected?.let { copy(url = it.url, mime = it.mime ?: mime) } ?: this
}

/** The one stream a Viewer page plays, so prefetch warms the same cache entry that playback reads. */
fun ImageRef.viewerPlaybackLocation(quality: VideoQuality, metered: Boolean, height: Int? = null): String? {
    val selected = withVideoQuality(quality, metered, height)
    return selected.localPath?.takeIf(String::isNotBlank) ?: selected.url?.takeIf(String::isNotBlank)
}

/**
 * Feed cards are small, so a metered network always previews the Data saver rendition regardless of the
 * Viewer's quality. Cards still autoplay together; unmetered feeds keep the post's default stream.
 */
fun ImageRef.feedPreviewLocation(metered: Boolean): String? {
    val selected = if (metered) withVideoQuality(VideoQuality.DATA_SAVER, metered = true) else this
    return selected.localPath ?: selected.url
}

fun blockedAnimationExport(
    settings: com.theoriacodex.data.repository.CacheSettings, metered: Boolean, roaming: Boolean,
): Boolean = metered && !settings.downloadsOverMetered || roaming && !settings.downloadsOverRoaming
