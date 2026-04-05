package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.pixiv.PIXIV_UGOIRA_MIME

enum class PostMediaKind {
    IMAGE,
    VIDEO,
    UGOIRA,
    UNKNOWN,
}

private val IMAGE_EXTENSIONS = setOf(
    "gif",
    "png",
    "webp",
    "jpg",
    "jpeg",
    "bmp",
    "heic",
    "heif",
    "avif",
)

private val VIDEO_EXTENSIONS = setOf(
    "mp4",
    "webm",
    "mov",
    "m4v",
)

fun mediaKind(mime: String?, location: String?): PostMediaKind {
    val normalizedMime = mime?.trim()?.lowercase()
    if (normalizedMime == PIXIV_UGOIRA_MIME) return PostMediaKind.UGOIRA
    if (normalizedMime?.startsWith("video/") == true) return PostMediaKind.VIDEO
    if (normalizedMime?.startsWith("image/") == true) return PostMediaKind.IMAGE

    val normalizedLocation = location
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.trim()
        ?.lowercase()
        .orEmpty()
    val extension = normalizedLocation.substringAfterLast('.', "")
    return when {
        extension in VIDEO_EXTENSIONS -> PostMediaKind.VIDEO
        extension in IMAGE_EXTENSIONS -> PostMediaKind.IMAGE
        else -> PostMediaKind.UNKNOWN
    }
}

fun mediaKind(ref: ImageRef): PostMediaKind {
    return mediaKind(
        mime = ref.mime,
        location = ref.localPath ?: ref.url,
    )
}

fun isVideoMediaRef(ref: ImageRef): Boolean = mediaKind(ref) == PostMediaKind.VIDEO

fun isGifMediaRef(ref: ImageRef): Boolean {
    val normalizedMime = ref.mime?.trim()?.lowercase()
    if (normalizedMime == "image/gif") return true
    val location = ref.localPath ?: ref.url
    val normalizedLocation = location
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.trim()
        ?.lowercase()
        .orEmpty()
    return normalizedLocation.endsWith(".gif")
}

fun isPixivUgoiraPost(post: Post): Boolean {
    if (post.id.source != SourceKey.PIXIV) return false
    if (post.full?.mime == PIXIV_UGOIRA_MIME) return true
    return post.media.any { media -> media.mime == PIXIV_UGOIRA_MIME }
}

fun isPixivUgoiraMedia(post: Post, media: ImageRef): Boolean {
    if (post.id.source != SourceKey.PIXIV) return false
    return media.mime == PIXIV_UGOIRA_MIME || post.full?.mime == PIXIV_UGOIRA_MIME
}

fun isAnimatedPost(post: Post): Boolean {
    if (
        post.id.source == SourceKey.RULE34VIDEO ||
        post.id.source == SourceKey.RULE34GEN ||
        post.id.source == SourceKey.IWARA
    ) {
        return true
    }
    if (isPixivUgoiraPost(post)) return true
    val refs = buildList {
        add(post.preview)
        post.full?.let { add(it) }
        addAll(post.media)
    }
    return refs.any { ref ->
        when (mediaKind(ref)) {
            PostMediaKind.UGOIRA, PostMediaKind.VIDEO -> true
            PostMediaKind.IMAGE -> isGifMediaRef(ref)
            PostMediaKind.UNKNOWN -> false
        }
    }
}
