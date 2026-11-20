package com.theoriacodex.app.media

import com.theoriacodex.app.source.presentation
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

enum class MediaDeliveryActivation {
    PRIMARY,
    QUALITY_UPGRADE,

    /** Full animated media replacing a visible poster; consecutive entries are mirrors of one file. */
    ANIMATION_UPGRADE,
    FAILURE_FALLBACK,
}

data class MediaDeliveryCandidate(
    val location: String,
    val ref: ImageRef,
    val activation: MediaDeliveryActivation,
    val requestHeaders: Map<String, String>,
)

data class MediaDeliveryPlan(
    val candidates: List<MediaDeliveryCandidate>,
) {
    val primary: MediaDeliveryCandidate?
        get() = candidates.firstOrNull()

    /** Candidates to try in order to replace the displayed one; the first success ends the attempt. */
    fun upgradeIndicesAfter(displayedIndex: Int): List<Int> {
        if (candidates.getOrNull(displayedIndex)?.activation == MediaDeliveryActivation.ANIMATION_UPGRADE) {
            return emptyList()
        }
        val nextIndex = displayedIndex + 1
        return when (candidates.getOrNull(nextIndex)?.activation) {
            MediaDeliveryActivation.QUALITY_UPGRADE -> listOf(nextIndex)
            MediaDeliveryActivation.ANIMATION_UPGRADE -> (nextIndex..candidates.lastIndex).takeWhile { index ->
                candidates[index].activation == MediaDeliveryActivation.ANIMATION_UPGRADE
            }
            else -> emptyList()
        }
    }
}

fun postPreviewImageCandidate(post: Post): PostMediaCandidate? {
    val full = post.full
    if (post.id.source == SourceKey.PIXIV && full != null) {
        full.progressiveUrls.firstOrNull(String::isNotBlank)?.let { mediumUrl ->
            val aspectPreservingPreview = full.copy(
                url = mediumUrl,
                progressiveUrls = full.progressiveUrls.drop(1) + listOfNotNull(full.url),
            )
            return post.candidate(
                aspectPreservingPreview,
                mediumUrl,
                PostMediaSelectionReason.FULL_MEDIA,
            )
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

/**
 * Orders what a feed card shows. Remote animated images must download completely before their first
 * frame, so the card paints its poster first and swaps to the animation once that is ready.
 *
 * Feed cards render at most half the screen wide, so quality upgrades stop at the largest downscaled
 * rendition (Gelbooru sample, Pixiv large); originals stay failure fallbacks and are left to the Viewer.
 * An original with no lighter rendition is the step up only when the post says it is small
 * ([FEED_SMALL_ORIGINAL_MAX_EDGE_PX]), which is when boorus skip generating a sample.
 */
fun previewMediaDeliveryPlan(post: Post): MediaDeliveryPlan {
    val animatedFull = post.full?.takeIf(::isAnimatedImageMediaRef)
    val hasLocalAnimation = !animatedFull?.localPath.isNullOrBlank()
    val preferred = if (hasLocalAnimation) animatedFull else postPreviewImageCandidate(post)?.ref
    val animationUpgrade = animatedFull?.takeUnless { hasLocalAnimation || it.url.isNullOrBlank() }
    val refs = buildList {
        preferred?.let(::add)
        add(post.preview)
        post.full?.let(::add)
        post.media.firstOrNull()?.let(::add)
    }.distinct()
    return mediaDeliveryPlan(
        post = post,
        refs = refs,
        qualityUpgradeRefs = if (post.id.source.presentation().cardsStayOnPreview) {
            emptySet()
        } else {
            refs.filterTo(linkedSetOf()) { ref -> ref != post.preview || ref == preferred }
        },
        preferredUrlFirst = preferred,
        animationUpgradeRef = animationUpgrade,
        nonUpgradeLocations = feedOriginalLocations(post),
        upgradesToSmallOriginal = post.hasSmallOriginal(),
    )
}

/** Long-edge size up to which a card may load an original that has no lighter rendition. */
internal const val FEED_SMALL_ORIGINAL_MAX_EDGE_PX = 1_280

private fun Post.hasSmallOriginal(): Boolean {
    val longEdge = maxOf(width ?: return false, height ?: return false)
    return longEdge in 1..FEED_SMALL_ORIGINAL_MAX_EDGE_PX
}

/** Originals that have a smaller rendition; a feed card never downloads them just to downsample. */
private fun feedOriginalLocations(post: Post): Set<String> =
    listOfNotNull(post.full, post.media.firstOrNull())
        .filter { ref -> ref.progressiveUrls.any(String::isNotBlank) }
        .mapNotNullTo(hashSetOf()) { ref -> normalizeMediaUrl(post.id.source, ref.url) }

fun viewerMediaDeliveryPlan(post: Post, media: ImageRef): MediaDeliveryPlan {
    val mediaIndex = post.media.indexOf(media)
    val samePageFallbacks = if (mediaIndex <= 0) {
        listOfNotNull(post.full, post.preview)
    } else {
        emptyList()
    }
    return mediaDeliveryPlan(
        post = post,
        refs = listOf(media) + samePageFallbacks,
        qualityUpgradeRefs = setOf(media),
        preferredUrlFirst = null,
    )
}

private fun mediaDeliveryPlan(
    post: Post,
    refs: List<ImageRef>,
    qualityUpgradeRefs: Set<ImageRef>,
    preferredUrlFirst: ImageRef?,
    animationUpgradeRef: ImageRef? = null,
    nonUpgradeLocations: Set<String> = emptySet(),
    upgradesToSmallOriginal: Boolean = false,
): MediaDeliveryPlan {
    val seen = linkedSetOf<String>()
    val candidates = buildList {
        refs.forEach refsLoop@ { ref ->
            if (mediaKind(ref) == PostMediaKind.VIDEO) return@refsLoop
            val normalizedRef = normalizedMediaRef(post, ref)
            val isAnimationUpgrade = ref == animationUpgradeRef
            val locations = imageLocations(normalizedRef, isAnimationUpgrade, urlFirst = ref == preferredUrlFirst)
            locations.forEach locationsLoop@ { (rawLocation, isFallbackCopy) ->
                val location = normalizeMediaUrl(post.id.source, rawLocation)
                    ?.takeIf(String::isNotBlank)
                    ?: return@locationsLoop
                if (!isLikelyImageLocation(normalizedRef.mime, location) || !seen.add(location)) {
                    return@locationsLoop
                }
                val activation = mediaDeliveryActivation(
                    isFirstCandidate = isEmpty(),
                    isAnimationUpgrade = isAnimationUpgrade,
                    isQualityUpgrade = ref in qualityUpgradeRefs &&
                        !isFallbackCopy &&
                        (normalizedRef.hasLighterRenditions() || upgradesToSmallOriginal) &&
                        normalizedRef.localPath.isNullOrBlank() &&
                        location !in nonUpgradeLocations,
                )
                add(
                    MediaDeliveryCandidate(
                        location = location,
                        ref = normalizedRef,
                        activation = activation,
                        requestHeaders = post.id.source.requestHeaders(),
                    )
                )
            }
        }
    }
    return MediaDeliveryPlan(candidates)
}

private fun mediaDeliveryActivation(
    isFirstCandidate: Boolean,
    isAnimationUpgrade: Boolean,
    isQualityUpgrade: Boolean,
): MediaDeliveryActivation = when {
    isFirstCandidate -> MediaDeliveryActivation.PRIMARY
    isAnimationUpgrade -> MediaDeliveryActivation.ANIMATION_UPGRADE
    isQualityUpgrade -> MediaDeliveryActivation.QUALITY_UPGRADE
    else -> MediaDeliveryActivation.FAILURE_FALLBACK
}

/** One place to load an image from; [isFallbackCopy] marks an equivalent copy that is only tried after a failure. */
private data class ImageLocation(val location: String, val isFallbackCopy: Boolean = false)

private fun imageLocations(ref: ImageRef, isAnimationUpgrade: Boolean, urlFirst: Boolean): List<ImageLocation> =
    if (isAnimationUpgrade) animatedImageLocations(ref) else orderedImageLocations(ref, urlFirst)

/** Lighter renditions, then the URL, then equivalent copies; [urlFirst] puts the URL ahead of the renditions. */
private fun orderedImageLocations(ref: ImageRef, urlFirst: Boolean): List<ImageLocation> = buildList {
    ref.localPath?.takeIf(String::isNotBlank)?.let { add(ImageLocation(it)) }
    val renditions = ref.progressiveUrls.filter(String::isNotBlank).map(::ImageLocation)
    val url = ref.url?.takeIf(String::isNotBlank)?.let { listOf(ImageLocation(it)) }.orEmpty()
    addAll(if (urlFirst) url + renditions else renditions + url)
    ref.fallbackUrls.filter(String::isNotBlank).mapTo(this) { ImageLocation(it, isFallbackCopy = true) }
}

/** The animated URL and its animated mirrors; a static sample must never replace a playing animation. */
private fun animatedImageLocations(ref: ImageRef): List<ImageLocation> = buildList {
    ref.url?.takeIf(String::isNotBlank)?.let { add(ImageLocation(it)) }
    (ref.progressiveUrls + ref.fallbackUrls)
        .filter(String::isNotBlank)
        .filterNot { location -> location.fileExtension() in STATIC_IMAGE_EXTENSIONS }
        .mapTo(this, ::ImageLocation)
}

/** Only a ref with lighter renditions has a step up; a lone URL or equivalent copies are just the file. */
private fun ImageRef.hasLighterRenditions(): Boolean = progressiveUrls.any(String::isNotBlank)

private fun String.fileExtension(): String =
    substringBefore('?').substringBefore('#').trim().lowercase().substringAfterLast('.', "")

fun isLikelyImageLocation(mime: String?, location: String): Boolean {
    val normalizedMime = mime?.trim()?.lowercase()
    if (normalizedMime?.startsWith("image/") == true) return true
    if (normalizedMime?.startsWith("video/") == true) return false
    return location.fileExtension() in IMAGE_EXTENSIONS
}

fun normalizeMediaUrl(source: SourceKey, location: String?): String? =
    if (source == SourceKey.GELBOORU) normalizeGelbooruMediaUrl(location) else location

private fun normalizedMediaRef(post: Post, ref: ImageRef): ImageRef {
    if (post.id.source != SourceKey.GELBOORU) return ref
    return ref.copy(
        url = normalizeMediaUrl(post.id.source, ref.url),
        progressiveUrls = ref.progressiveUrls.mapNotNull { normalizeMediaUrl(post.id.source, it) },
        fallbackUrls = ref.fallbackUrls.mapNotNull { normalizeMediaUrl(post.id.source, it) },
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
private val STATIC_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp")
