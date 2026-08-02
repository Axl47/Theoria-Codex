package com.theoriacodex.app.media

import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.gelbooru.normalizeGelbooruMediaUrl

enum class PostMediaSelectionReason {
    EXPLICIT_MEDIA,
    FULL_MEDIA,
    PREVIEW_MEDIA,
    FULL_ANIMATED_IMAGE,
}

data class PostMediaCandidate(
    val postId: PostId,
    val source: SourceKey,
    val ref: ImageRef,
    val url: String,
    val kind: PostMediaKind,
    val requestHeaders: Map<String, String>,
    val reason: PostMediaSelectionReason,
)

fun postPreviewImageCandidate(post: Post): PostMediaCandidate? {
    val full = post.full
    if (full != null && isAnimatedImageMediaRef(full)) {
        full.url?.takeIf(String::isNotBlank)?.let { url ->
            return post.candidate(full, url, PostMediaSelectionReason.FULL_ANIMATED_IMAGE)
        }
    }
    return buildList {
        add(post.preview to PostMediaSelectionReason.PREVIEW_MEDIA)
        post.full?.let { add(it to PostMediaSelectionReason.FULL_MEDIA) }
        post.media.forEach { add(it to PostMediaSelectionReason.EXPLICIT_MEDIA) }
    }.firstNotNullOfOrNull { (ref, reason) ->
        ref.url
            ?.takeIf(String::isNotBlank)
            ?.takeUnless { mediaKind(ref) == PostMediaKind.VIDEO }
            ?.let { url -> post.candidate(ref, url, reason) }
    }
}

fun postPlaybackMediaCandidate(post: Post): PostMediaCandidate? {
    return post.orderedPlayableRefs().firstNotNullOfOrNull { (ref, reason) ->
        (ref.localPath?.takeIf(String::isNotBlank) ?: ref.url?.takeIf(String::isNotBlank))
            ?.takeIf { mediaKind(ref) == PostMediaKind.VIDEO }
            ?.let { url -> post.candidate(ref, url, reason) }
    }
}

fun postDownloadMediaCandidate(post: Post): PostMediaCandidate? {
    return post.orderedPlayableRefs().firstNotNullOfOrNull { (ref, reason) ->
        ref.url?.takeIf(String::isNotBlank)?.let { url -> post.candidate(ref, url, reason) }
    }
}

fun postShareMediaCandidate(post: Post): PostMediaCandidate? = postDownloadMediaCandidate(post)

fun postMediaItems(post: Post): List<ImageRef> {
    val explicitMedia = post.media.map { ref -> normalizedMediaRef(post, ref) }
        .filter(ImageRef::hasAnyLocation)
    if (explicitMedia.isNotEmpty()) return explicitMedia
    return listOfNotNull(post.full?.let { normalizedMediaRef(post, it) })
        .ifEmpty { listOf(normalizedMediaRef(post, post.preview)) }
}

fun progressiveImageCandidates(post: Post, media: ImageRef): List<String> {
    if (supportsProgressiveImageCandidates(post, media)) {
        val progressiveCandidates = buildList {
            media.localPath?.takeIf(String::isNotBlank)?.let(::add)
            addAll(media.progressiveUrls.filter(String::isNotBlank))
            media.url?.takeIf(String::isNotBlank)?.let(::add)
        }.distinct()
        if (progressiveCandidates.isNotEmpty()) return progressiveCandidates
    }
    val refs = buildList {
        add(media)
        post.full?.let(::add)
        add(post.preview)
    }
    val preferred = refs.mapNotNull { ref ->
        ref.bestLocation()?.takeIf { location -> isLikelyImageLocation(ref.mime, location) }
    }.distinct()
    return preferred.ifEmpty { refs.mapNotNull(ImageRef::bestLocation).distinct() }
}

fun supportsProgressiveImageCandidates(post: Post, media: ImageRef): Boolean {
    return post.id.source in PROGRESSIVE_IMAGE_SOURCES &&
        (media.progressiveUrls.isNotEmpty() || !media.localPath.isNullOrBlank())
}

fun isLikelyImageLocation(mime: String?, location: String): Boolean {
    val normalizedMime = mime?.trim()?.lowercase()
    if (normalizedMime?.startsWith("image/") == true) return true
    if (normalizedMime?.startsWith("video/") == true) return false
    return location.substringBefore('?').substringBefore('#').trim().lowercase()
        .substringAfterLast('.', "") in IMAGE_EXTENSIONS
}

fun normalizeMediaUrl(source: SourceKey, location: String?): String? =
    if (source == SourceKey.GELBOORU) normalizeGelbooruMediaUrl(location) else location

private fun normalizedMediaRef(post: Post, ref: ImageRef): ImageRef {
    if (post.id.source != SourceKey.GELBOORU) return ref
    return ref.copy(
        url = normalizeMediaUrl(post.id.source, ref.url),
        progressiveUrls = ref.progressiveUrls.mapNotNull { normalizeMediaUrl(post.id.source, it) },
    )
}

private fun Post.orderedPlayableRefs(): List<Pair<ImageRef, PostMediaSelectionReason>> = buildList {
    media.forEach { add(it to PostMediaSelectionReason.EXPLICIT_MEDIA) }
    full?.let { add(it to PostMediaSelectionReason.FULL_MEDIA) }
    add(preview to PostMediaSelectionReason.PREVIEW_MEDIA)
}

private fun Post.candidate(
    ref: ImageRef,
    url: String,
    reason: PostMediaSelectionReason,
): PostMediaCandidate {
    val normalizedRef = normalizedMediaRef(this, ref)
    return PostMediaCandidate(
        postId = id,
        source = id.source,
        ref = normalizedRef,
        url = normalizeMediaUrl(id.source, url) ?: url,
        kind = mediaKind(normalizedRef),
        requestHeaders = id.source.requestHeaders(),
        reason = reason,
    )
}

private fun ImageRef.hasAnyLocation(): Boolean = !url.isNullOrBlank() || !localPath.isNullOrBlank()

private fun ImageRef.bestLocation(): String? =
    localPath?.takeIf(String::isNotBlank) ?: url?.takeIf(String::isNotBlank)

private val IMAGE_EXTENSIONS = setOf("gif", "png", "webp", "jpg", "jpeg", "bmp", "heic", "heif", "avif")
private val PROGRESSIVE_IMAGE_SOURCES = setOf(
    SourceKey.PIXIV,
    SourceKey.GELBOORU,
    SourceKey.NHENTAI,
    SourceKey.HITOMI,
)
