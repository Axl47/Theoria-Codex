package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME

enum class PostMediaKind {
    IMAGE,
    VIDEO,
    UGOIRA,
    UNKNOWN,
}

data class AnimatedDurationRange(
    val minBucket: Int = ANIMATED_DURATION_MIN_BUCKET,
    val maxBucket: Int = ANIMATED_DURATION_MAX_BUCKET,
) {
    val normalizedMinBucket: Int
        get() = minBucket.coerceIn(ANIMATED_DURATION_MIN_BUCKET, ANIMATED_DURATION_MAX_BUCKET)

    val normalizedMaxBucket: Int
        get() = maxBucket.coerceIn(ANIMATED_DURATION_MIN_BUCKET, ANIMATED_DURATION_MAX_BUCKET)

    val isFullRange: Boolean
        get() = normalizedMinBucket == ANIMATED_DURATION_MIN_BUCKET &&
            normalizedMaxBucket == ANIMATED_DURATION_MAX_BUCKET

    fun contains(durationMs: Long): Boolean {
        val lower = minOf(normalizedMinBucket, normalizedMaxBucket)
        val upper = maxOf(normalizedMinBucket, normalizedMaxBucket)
        val matchesLower = when (lower) {
            ANIMATED_DURATION_MIN_BUCKET -> true
            ANIMATED_DURATION_MAX_BUCKET -> durationMs > ANIMATED_DURATION_MAX_THRESHOLD_MS
            else -> durationMs >= lower * ANIMATED_DURATION_BUCKET_MS
        }
        val matchesUpper = when (upper) {
            ANIMATED_DURATION_MIN_BUCKET -> durationMs < ANIMATED_DURATION_BUCKET_MS
            ANIMATED_DURATION_MAX_BUCKET -> true
            else -> durationMs <= upper * ANIMATED_DURATION_BUCKET_MS
        }
        return matchesLower && matchesUpper
    }

    companion object {
        val Full = AnimatedDurationRange()
    }
}

const val ANIMATED_DURATION_MIN_BUCKET = 0
const val ANIMATED_DURATION_MAX_BUCKET = 25
private const val ANIMATED_DURATION_BUCKET_MS = 5_000L
private const val ANIMATED_DURATION_LAST_EXACT_BUCKET = 24
private const val ANIMATED_DURATION_MAX_THRESHOLD_MS =
    ANIMATED_DURATION_LAST_EXACT_BUCKET * ANIMATED_DURATION_BUCKET_MS

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

fun isAnimatedImageMediaRef(ref: ImageRef): Boolean {
    return mediaKind(ref) == PostMediaKind.IMAGE && (ref.isAnimated || isGifMediaRef(ref))
}

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
            PostMediaKind.IMAGE -> isAnimatedImageMediaRef(ref)
            PostMediaKind.UNKNOWN -> false
        }
    }
}

fun animatedDurationMs(post: Post): Long? {
    return post.durationMs?.takeIf { it > 0L }
}

fun animatedDurationLabel(post: Post): String? {
    if (!isAnimatedPost(post)) return null
    val durationMs = animatedDurationMs(post) ?: return null
    return formatCompactDuration(durationMs)
}

fun mergeResolvedPostForPresentation(original: Post, resolved: Post): Post {
    if (resolved.id != original.id) return original
    val originalDuration = animatedDurationMs(original)
    return if (animatedDurationMs(resolved) == null && originalDuration != null) {
        resolved.copy(durationMs = originalDuration)
    } else {
        resolved
    }
}

private fun formatCompactDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(1L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return buildString {
        if (hours > 0L) append("${hours}h")
        if (minutes > 0L) append("${minutes}m")
        if (seconds > 0L || isEmpty()) append("${seconds}s")
    }
}

fun durationBucketFor(durationMs: Long): Int {
    if (durationMs < ANIMATED_DURATION_BUCKET_MS) return ANIMATED_DURATION_MIN_BUCKET
    if (durationMs > ANIMATED_DURATION_MAX_THRESHOLD_MS) {
        return ANIMATED_DURATION_MAX_BUCKET
    }
    return (durationMs / ANIMATED_DURATION_BUCKET_MS)
        .toInt()
        .coerceIn(ANIMATED_DURATION_MIN_BUCKET, ANIMATED_DURATION_LAST_EXACT_BUCKET)
}

fun animatedDurationBucketLabel(bucket: Int): String {
    val normalized = bucket.coerceIn(ANIMATED_DURATION_MIN_BUCKET, ANIMATED_DURATION_MAX_BUCKET)
    return when (normalized) {
        ANIMATED_DURATION_MIN_BUCKET -> "<5s"
        ANIMATED_DURATION_MAX_BUCKET -> ">2m"
        else -> formatDurationSeconds(normalized * 5)
    }
}

fun animatedDurationRangeLabel(range: AnimatedDurationRange): String {
    val lower = minOf(range.normalizedMinBucket, range.normalizedMaxBucket)
    val upper = maxOf(range.normalizedMinBucket, range.normalizedMaxBucket)
    return "${animatedDurationBucketLabel(lower)} - ${animatedDurationBucketLabel(upper)}"
}

private fun formatDurationSeconds(totalSeconds: Int): String {
    if (totalSeconds < 60) return "${totalSeconds}s"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (seconds == 0) {
        "${minutes}m"
    } else {
        "${minutes}m ${seconds}s"
    }
}
