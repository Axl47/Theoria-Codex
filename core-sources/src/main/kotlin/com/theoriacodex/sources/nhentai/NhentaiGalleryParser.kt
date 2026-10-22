package com.theoriacodex.sources.nhentai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.common.asBooleanOrNull
import com.theoriacodex.sources.common.asIntOrNull
import com.theoriacodex.sources.common.asLongOrNull
import com.theoriacodex.sources.common.asStringOrNull
import com.theoriacodex.sources.common.elementsOrEmpty
import com.theoriacodex.sources.common.optionalJsonArray
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.media.mimeFromFileExt

/** Pure gallery mapping shared by API results and sparse mirrored-search records. */
internal fun parseNhentaiGallery(raw: JsonObject): Post? {
    val galleryId = raw.get("id").asLongOrNull()?.toString()
        ?: raw.get("id").asStringOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: return null
    val mediaId = raw.get("media_id").asLongOrNull()?.toString()
        ?: raw.get("media_id").asStringOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: return null
    val images = parseNhentaiImages(raw, mediaId)
    val tags = parseNhentaiTags(raw)
    val canonicalTags = tags.map { it.name }.distinctBy { it.lowercase() }
    val rawTags = canonicalTags
    val titleObject = raw.optionalJsonObject("title")
    val title = listOf(
        raw.get("english_title").asStringOrNull(),
        titleObject?.get("english").asStringOrNull(),
        titleObject?.get("pretty").asStringOrNull(),
        titleObject?.get("japanese").asStringOrNull(),
        raw.get("japanese_title").asStringOrNull(),
    ).firstOrNull { !it.isNullOrBlank() }

    val taxonomy = tags.map(ParsedNhentaiTag::toPostTaxonomyTerm)
    val creators = taxonomy.nhentaiCreatorProfiles()
    val authorName = creators.firstOrNull()?.displayName
    val createdAtEpochMs = raw.get("upload_date").asLongOrNull()?.times(1000L)
    return Post(
        id = PostId(SourceKey.NHENTAI, galleryId),
        preview = images.preview,
        full = images.media.firstOrNull(),
        media = images.media,
        pageUrl = "https://nhentai.net/g/$galleryId/",
        width = images.width,
        height = images.height,
        canonicalTags = canonicalTags,
        rawTags = rawTags,
        authorName = authorName,
        createdAtEpochMs = createdAtEpochMs,
        title = cleanNhentaiTitle(title),
        mediaCount = images.count,
        taxonomy = taxonomy,
        creatorProfile = creators.firstOrNull(),
        creatorProfiles = creators,
    )
}

private data class NhentaiGalleryImages(
    val preview: ImageRef,
    val media: List<ImageRef>,
    val count: Int?,
    val width: Int?,
    val height: Int?,
)

private fun parseNhentaiImages(raw: JsonObject, mediaId: String): NhentaiGalleryImages {
    val oldImages = raw.optionalJsonObject("images")
    val oldThumbnail = oldImages?.optionalJsonObject("thumbnail")
    val oldCover = oldImages?.optionalJsonObject("cover")
    val oldPages = oldImages?.optionalJsonArray("pages").elementsOrEmpty()
    val newPages = raw.optionalJsonArray("pages").elementsOrEmpty()
    val pages = if (newPages.isNotEmpty()) newPages else oldPages

    val oldThumbExt = imageExtension(oldThumbnail?.get("t").asStringOrNull())
    val oldCoverExt = imageExtension(oldCover?.get("t").asStringOrNull())
    val newThumbnailPath = raw.get("thumbnail").asStringOrNull()
        ?: raw.optionalJsonObject("thumbnail")?.get("path").asStringOrNull()
    val newCoverPath = raw.get("cover").asStringOrNull()
        ?: raw.optionalJsonObject("cover")?.get("path").asStringOrNull()
    val previewUrl = sourceImageUrl(NHENTAI_THUMBS_BASE, newThumbnailPath)
        ?: "$NHENTAI_THUMBS_BASE/galleries/$mediaId/thumb.$oldThumbExt"
    val coverUrl = sourceImageUrl(NHENTAI_THUMBS_BASE, newCoverPath)
        ?: "$NHENTAI_THUMBS_BASE/galleries/$mediaId/cover.$oldCoverExt"

    val pageRefs = parseNhentaiPages(pages, mediaId)

    val fallbackCoverRef = ImageRef(
        url = coverUrl,
        localPath = null,
        mime = mimeFromFileExt(newCoverPath?.substringAfterLast('.') ?: oldCoverExt),
    )

    val mediaRefs = selectNhentaiMedia(raw, pageRefs, fallbackCoverRef, oldCover != null)

    val fullRef = mediaRefs.firstOrNull()
    val previewRef = ImageRef(
        url = previewUrl,
        localPath = null,
        mime = mimeFromFileExt(newThumbnailPath?.substringAfterLast('.') ?: oldThumbExt) ?: fullRef?.mime,
    )

    val declaredMediaCount = raw.get("num_pages").asIntOrNull()?.takeIf { it > 0 }
    val mediaCount = when {
        pageRefs.isNotEmpty() -> pageRefs.size
        declaredMediaCount != null -> declaredMediaCount
        mediaRefs.isNotEmpty() -> mediaRefs.size
        else -> null
    }

    return NhentaiGalleryImages(
        preview = previewRef,
        media = mediaRefs,
        count = mediaCount,
        width = raw.get("thumbnail_width").asIntOrNull()
            ?: oldThumbnail?.get("w").asIntOrNull()
            ?: oldCover?.get("w").asIntOrNull(),
        height = raw.get("thumbnail_height").asIntOrNull()
            ?: oldThumbnail?.get("h").asIntOrNull()
            ?: oldCover?.get("h").asIntOrNull(),
    )
}

private fun parseNhentaiPages(pages: List<JsonElement>, mediaId: String): List<ImageRef> {
    return pages.sortedWithPageNumber().mapIndexedNotNull { index, pageElement ->
        val page = pageElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapIndexedNotNull null
        val path = page.get("path").asStringOrNull()
        val ext = imageExtension(path?.substringAfterLast('.') ?: page.get("t").asStringOrNull())
        val url = sourceImageUrl(NHENTAI_IMAGES_BASE, path)
            ?: "$NHENTAI_IMAGES_BASE/galleries/$mediaId/${index + 1}.$ext"
        ImageRef(
            url = url,
            localPath = null,
            mime = mimeFromFileExt(ext),
        )
    }
}

private fun selectNhentaiMedia(
    raw: JsonObject,
    pageRefs: List<ImageRef>,
    fallbackCoverRef: ImageRef,
    hasOldCover: Boolean,
): List<ImageRef> {
    val mirrorSparse = raw.get("mirror_sparse").asBooleanOrNull() ?: false
    return if (mirrorSparse) {
        emptyList()
    } else if (pageRefs.isNotEmpty()) {
        pageRefs
    } else if (raw.has("cover") || hasOldCover) {
        listOf(fallbackCoverRef)
    } else {
        emptyList()
    }
}

private fun parseNhentaiTags(raw: JsonObject): List<ParsedNhentaiTag> {
    return raw.optionalJsonArray("tags").elementsOrEmpty().mapNotNull { tagElement ->
        val tag = tagElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
        val name = tag.get("name").asStringOrNull()?.trim().orEmpty()
        if (name.isBlank()) return@mapNotNull null
        ParsedNhentaiTag(
            name = name,
            type = tag.get("type").asStringOrNull()?.trim(),
            count = tag.get("count").asIntOrNull()?.takeIf { it >= 0 },
        )
    }
}

private data class ParsedNhentaiTag(
    val name: String,
    val type: String?,
    val count: Int?,
)

private fun ParsedNhentaiTag.toPostTaxonomyTerm(): PostTaxonomyTerm {
    val taxonomy = nhentaiTaxonomy(type)
    return PostTaxonomyTerm(
        value = name,
        facet = taxonomy.first,
        sourceNamespace = taxonomy.second,
        count = count,
    )
}

private fun List<JsonElement>.sortedWithPageNumber(): List<JsonElement> {
    return sortedWith(compareBy { element ->
        element.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?.get("number")
            .asIntOrNull()
            ?: Int.MAX_VALUE
    })
}

private fun sourceImageUrl(baseUrl: String, path: String?): String? {
    val normalizedPath = path
        ?.trim()
        ?.trimStart('/')
        ?.takeIf(String::isNotBlank)
        ?: return null
    if (normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://")) {
        return normalizedPath
    }
    return "${baseUrl.trimEnd('/')}/$normalizedPath"
}
