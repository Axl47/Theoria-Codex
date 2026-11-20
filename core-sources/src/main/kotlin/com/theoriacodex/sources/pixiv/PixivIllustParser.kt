package com.theoriacodex.sources.pixiv

import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.common.asLongOrNull
import com.theoriacodex.sources.common.asStringOrNull
import com.theoriacodex.sources.common.elementsOrEmpty
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.longValue
import com.theoriacodex.sources.common.optionalJsonArray
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.media.inferMimeFromUrl
import java.time.Instant

/** The posts in a response's `illusts` array, skipping records that are not illustrations. */
internal fun JsonObject.pixivIllusts(): List<Post> =
    optionalJsonArray("illusts").elementsOrEmpty().mapNotNull { element ->
        element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parsePixivIllust)
    }

internal fun parsePixivTagSuggestion(tag: JsonObject, type: String): TagSuggestion? {
    val name = tag.stringValue("name")?.trim().orEmpty()
    if (name.isBlank()) return null
    val translatedName = tag.stringValue("translated_name")
        ?.trim()
        ?.takeIf { translated ->
            translated.isNotBlank() && !translated.equals(name, ignoreCase = true)
        }
    return TagSuggestion(
        text = name,
        type = type,
        count = null,
        alternateText = translatedName,
    )
}

internal fun parsePixivIllust(raw: JsonObject): Post? {
    val id = raw.longValue("id")?.toString() ?: return null
    val media = parsePixivMedia(raw)
    val tags = parsePixivTags(raw)
    val createdAt = parseIsoInstant(raw.stringValue("create_date"))
    val user = raw.optionalJsonObject("user")
    val userName = user?.stringValue("name")?.trim().orEmpty().ifBlank { null }
    val userId = user?.longValue("id")?.toString()
    val title = raw.stringValue("title")

    return Post(
        id = PostId(SourceKey.PIXIV, id),
        preview = media.preview,
        full = media.full,
        media = media.pages,
        pageUrl = "https://www.pixiv.net/en/artworks/$id",
        width = raw.intValue("width"),
        height = raw.intValue("height"),
        canonicalTags = tags.canonical,
        rawTags = tags.raw,
        taxonomy = tags.taxonomy,
        authorName = userName,
        createdAtEpochMs = createdAt,
        title = title,
        creatorProfile = userName?.let { pixivUserProfile(userId, it) },
        durationMs = null,
    )
}

/** Total playback time of a `/v1/ugoira/metadata` response, or null when it lists no frames. */
internal fun parsePixivUgoiraDurationMs(root: JsonObject): Long? {
    val metadata = root.optionalJsonObject("ugoira_metadata") ?: return null
    val frames = metadata.optionalJsonArray("frames").elementsOrEmpty()
    return frames.sumOf { frame ->
        frame.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("delay")
            .asLongOrNull()
            ?.coerceAtLeast(16L)
            ?: 0L
    }.takeIf { it > 0L }
}

private fun buildPixivProgressiveUrls(
    vararg candidateUrls: String?,
    canonicalUrl: String?,
): List<String> {
    return candidateUrls
        .mapNotNull(::normalizedUrl)
        .filter { it != canonicalUrl }
        .distinct()
}

private fun normalizedUrl(url: String?): String? {
    return url?.trim()?.takeIf(String::isNotBlank)
}

private fun parseIsoInstant(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

private data class ParsedPixivMedia(val preview: ImageRef, val full: ImageRef?, val pages: List<ImageRef>)
/**
 * [canonical] keeps each translation right after its native tag (Codex automatic tags and the
 * clipboard match English there); [taxonomy] pairs them so chips and learning see one tag.
 */
private data class ParsedPixivTags(
    val raw: List<String>,
    val canonical: List<String>,
    val taxonomy: List<PostTaxonomyTerm>,
)

private fun parsePixivMedia(raw: JsonObject): ParsedPixivMedia {
    val isUgoira = raw.stringValue("type") == "ugoira"
    val imageUrls = raw.optionalJsonObject("image_urls")
    val previewUrl = normalizedUrl(
        imageUrls?.stringValue("medium") ?: imageUrls?.stringValue("square_medium"),
    )
    val singlePageMediumUrl = normalizedUrl(imageUrls?.stringValue("medium"))
    val singlePageLargeUrl = normalizedUrl(imageUrls?.stringValue("large"))
    val singlePageOriginalUrl = normalizedUrl(
        raw.optionalJsonObject("meta_single_page")
            ?.stringValue("original_image_url"),
    )
    val fullUrl = singlePageOriginalUrl ?: singlePageLargeUrl ?: singlePageMediumUrl
    val fullMime = if (isUgoira) PIXIV_UGOIRA_MIME else inferMimeFromUrl(fullUrl)
    val previewMime = inferMimeFromUrl(previewUrl) ?: fullMime
    val previewRef = ImageRef(url = previewUrl, localPath = null, mime = previewMime)
    val singlePageRef = fullUrl?.let { canonicalUrl ->
        ImageRef(
            url = canonicalUrl,
            localPath = null,
            mime = fullMime,
            progressiveUrls = if (isUgoira) {
                emptyList()
            } else {
                buildPixivProgressiveUrls(
                    singlePageMediumUrl,
                    singlePageLargeUrl,
                    canonicalUrl = canonicalUrl,
                )
            },
        )
    }
    val multiPageRefs = parsePixivPages(raw)
    val fallbackFullRef = singlePageRef
    val mediaRefs = when {
        multiPageRefs.isNotEmpty() -> multiPageRefs
        fallbackFullRef != null -> listOf(fallbackFullRef)
        !previewUrl.isNullOrBlank() -> listOf(previewRef)
        else -> emptyList()
    }

    return ParsedPixivMedia(previewRef, fallbackFullRef ?: mediaRefs.firstOrNull(), mediaRefs)
}

private fun parsePixivPages(raw: JsonObject): List<ImageRef> {
    return raw.optionalJsonArray("meta_pages").elementsOrEmpty().mapNotNull { page ->
        val pageObject = page.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val pageUrls = pageObject.optionalJsonObject("image_urls") ?: return@mapNotNull null
        val mediumUrl = normalizedUrl(pageUrls.stringValue("medium"))
        val largeUrl = normalizedUrl(pageUrls.stringValue("large"))
        val originalUrl = normalizedUrl(pageUrls.stringValue("original"))
        val pageUrl = originalUrl ?: largeUrl ?: mediumUrl ?: return@mapNotNull null
        ImageRef(
            url = pageUrl,
            localPath = null,
            mime = inferMimeFromUrl(pageUrl),
            progressiveUrls = buildPixivProgressiveUrls(
                mediumUrl,
                largeUrl,
                canonicalUrl = pageUrl,
            ),
        )
    }
}

private fun parsePixivTags(raw: JsonObject): ParsedPixivTags {
    val translationsByNative = linkedMapOf<String, String?>()
    val canonicalTags = linkedSetOf<String>()
    raw.optionalJsonArray("tags").elementsOrEmpty().forEach { tagElement ->
        val tagObject = tagElement.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
        val name = tagObject.stringValue("name")?.trim().orEmpty()
        val translatedName = tagObject.get("translated_name").asStringOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
        if (name.isNotBlank()) {
            canonicalTags += name
            if (name !in translationsByNative || translationsByNative[name] == null) {
                translationsByNative[name] = translatedName
            }
        }
        translatedName?.let { canonicalTags += it }
    }
    return ParsedPixivTags(
        raw = translationsByNative.keys.toList(),
        canonical = canonicalTags.toList(),
        taxonomy = translationsByNative.map { (name, translation) ->
            PostTaxonomyTerm(value = name, translation = translation)
        },
    )
}
