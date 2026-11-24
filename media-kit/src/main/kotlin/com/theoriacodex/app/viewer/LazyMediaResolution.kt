package com.theoriacodex.app.viewer

import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

private val LAZY_MEDIA_RESOLUTION_SOURCES = setOf(
    SourceKey.NHENTAI,
    SourceKey.HITOMI,
    SourceKey.RULE34VIDEO,
    SourceKey.RULE34GEN,
    SourceKey.IWARA,
)

private val REFRESHABLE_REMOTE_MEDIA_SOURCES = setOf(
    SourceKey.AIBOORU,
    SourceKey.DANBOORU,
    SourceKey.GELBOORU,
    SourceKey.HITOMI,
    SourceKey.IWARA,
    SourceKey.RULE34XXX,
    SourceKey.RULE34PAHEAL,
    SourceKey.RULE34VIDEO,
    SourceKey.RULE34GEN,
)

fun SourceKey.hasRefreshableRemoteMedia(): Boolean = this in REFRESHABLE_REMOTE_MEDIA_SOURCES || isCustom

fun requiresLazyMediaResolution(post: Post): Boolean {
    val mediaRefs = buildList {
        addAll(post.media)
        post.full?.let { add(it) }
    }
    if (mediaRefs.any { ref -> ref.streams.isNotEmpty() }) return false
    if (
        post.id.source.hasRefreshableRemoteMedia() &&
        mediaRefs.any { ref -> isVideoMediaRef(ref) && ref.localPath.isNullOrBlank() && !ref.url.isNullOrBlank() }
    ) {
        return true
    }
    if (post.galleryLengthUnknown && mediaRefs.none { ref -> !ref.url.isNullOrBlank() }) return true
    if (post.id.source !in LAZY_MEDIA_RESOLUTION_SOURCES) return false
    if (post.id.source == SourceKey.NHENTAI &&
        (post.mediaCount ?: 0) > post.media.size.coerceAtLeast(if (post.full != null) 1 else 0)
    ) return true
    return mediaRefs.none { ref ->
        !ref.url.isNullOrBlank()
    }
}
