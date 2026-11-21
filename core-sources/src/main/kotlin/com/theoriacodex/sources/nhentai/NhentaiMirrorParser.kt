package com.theoriacodex.sources.nhentai

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.common.parseHtml
import com.theoriacodex.sources.media.mimeFromFileExt

/** One page of search results, from the API or a mirror; [totalPages] is null when the page did not say. */
internal data class NhentaiSearchPage(
    val posts: List<Post>,
    val totalPages: Int?,
)

internal object NhentaiMirrorParser {
    fun parseGalleryMetadata(body: String): NhentaiMirrorMetadata? {
        val document = parseHtml(body)
        val taxonomy = document.select("section#tags a.tag, li.tags a.tag_btn")
            .mapNotNull { link ->
                val href = link.attr("href").trim()
                if (!href.isNhentaiTaxonomyPath()) return@mapNotNull null
                val name = link.selectFirst(".name")?.text()?.trim() ?: link.ownText().trim()
                val normalizedName = name.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val mapped = nhentaiTaxonomy(href.nhentaiTaxonomyNamespace())
                PostTaxonomyTerm(
                    value = normalizedName,
                    facet = mapped.first,
                    sourceNamespace = mapped.second,
                )
            }
            .distinctBy { term -> Triple(term.facet, term.sourceNamespace, term.value.lowercase()) }
        if (taxonomy.isEmpty()) return null

        return NhentaiMirrorMetadata(
            taxonomy = taxonomy,
            authorName = taxonomy.firstOrNull { term -> term.facet == SearchFacet.ARTIST }?.value,
        )
    }

    /** Search results carry only an ID, title and cover thumbnail; their media load when the post opens. */
    fun parseSearchPage(body: String): NhentaiSearchPage = NhentaiSearchPage(
        posts = body.lineSequence().mapNotNull(::parseSearchPost).toList(),
        totalPages = parsePageCount(body),
    )

    /**
     * A reader page names the first page's image and the page count; every page shares that image's media ID
     * and extension, with the other extensions as fallbacks.
     */
    fun parseReaderPage(galleryId: String, body: String): Post? {
        val firstPage = PAGE_IMAGE_REGEX.find(body) ?: return null
        val mediaId = firstPage.groupValues[1]
        val firstPageExt = firstPage.groupValues[2].lowercase()
        val pageCount = PAGE_COUNT_REGEX.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 1
        val title = parseTitle(body)
            ?.removeSuffix(" - Page 1")
            ?.removeSuffix(" » nhentai")
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val galleryBase = "$NHENTAI_IMAGES_BASE/galleries/$mediaId"
        val mediaRefs = (1..pageCount).map { page ->
            ImageRef(
                url = "$galleryBase/$page.$firstPageExt",
                localPath = null,
                mime = mimeFromFileExt(firstPageExt),
                fallbackUrls = (listOf(firstPageExt) + IMAGE_FALLBACK_EXTENSIONS).distinct()
                    .map { ext -> "$galleryBase/$page.$ext" },
                thumbnailUrl = "$NHENTAI_THUMBS_BASE/galleries/$mediaId/${page}t.$firstPageExt",
            )
        }
        return Post(
            id = PostId(SourceKey.NHENTAI, galleryId),
            preview = ImageRef(
                url = "$NHENTAI_THUMBS_BASE/galleries/$mediaId/thumb.$firstPageExt",
                localPath = null,
                mime = mimeFromFileExt(firstPageExt),
            ),
            full = mediaRefs.firstOrNull(),
            media = mediaRefs,
            pageUrl = "https://nhentai.net/g/$galleryId/",
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = cleanNhentaiTitle(title),
            mediaCount = pageCount,
        )
    }

    private fun parseTitle(body: String): String? {
        return body.lineSequence()
            .firstOrNull { line -> line.startsWith("Title: ") }
            ?.removePrefix("Title: ")
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun parseSearchPost(line: String): Post? {
        val galleryId = GALLERY_LINK_REGEX.find(line)?.groupValues?.getOrNull(1) ?: return null
        val previewMatch = THUMB_REGEX.find(line) ?: return null
        val previewUrl = previewMatch.groupValues[1]
        val mediaId = previewMatch.groupValues[2]
        val thumbExt = imageExtension(previewUrl.substringAfterLast('.').substringBefore('?'))
        return Post(
            id = PostId(SourceKey.NHENTAI, galleryId),
            preview = ImageRef(
                url = "$NHENTAI_THUMBS_BASE/galleries/$mediaId/thumb.$thumbExt",
                localPath = null,
                mime = mimeFromFileExt(thumbExt),
            ),
            full = null,
            pageUrl = "https://nhentai.net/g/$galleryId/",
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = cleanNhentaiTitle(parseSearchTitle(line, previewUrl)),
            galleryLengthUnknown = true,
        )
    }

    private fun parseSearchTitle(line: String, previewUrl: String): String? {
        val beforePreview = line.substringBefore("]($previewUrl)", missingDelimiterValue = "")
        val titleStart = beforePreview.indexOf(": ").takeIf { it >= 0 }?.plus(2) ?: return null
        val titleEnd = beforePreview.lastIndexOf("](").takeIf { it > titleStart } ?: beforePreview.length
        return beforePreview.substring(titleStart, titleEnd).trim().takeIf(String::isNotBlank)
    }

    private fun parsePageCount(body: String): Int {
        val linkedMaxPage = PAGE_LINK_REGEX.findAll(body)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
        if (linkedMaxPage != null) return linkedMaxPage.coerceAtLeast(1)

        val totalResults = RESULT_COUNT_REGEX.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", "")
            ?.toIntOrNull()
        return if (totalResults != null) {
            ((totalResults + NHENTAI_SEARCH_PAGE_SIZE - 1) / NHENTAI_SEARCH_PAGE_SIZE).coerceAtLeast(1)
        } else {
            1
        }
    }

    private val THUMB_REGEX = Regex("""(https://t\d*\.nhentai\.net/galleries/(\d+)/thumb[^\)]*)""")
    private val GALLERY_LINK_REGEX = Regex("""https?://nhentai\.net/g/(\d+)/""")
    private val PAGE_LINK_REGEX = Regex("""[?&]page=(\d+)""")
    private val RESULT_COUNT_REGEX = Regex("""(?m)^#+\s*([\d,]+)\s+results\b""")
    private val PAGE_IMAGE_REGEX = Regex("""https://i\d*\.nhentai\.net/galleries/(\d+)/1\.(webp|jpg|jpeg|png|gif)""")
    private val PAGE_COUNT_REGEX = Regex("""\b1\s+of\s+(\d+)\b""")
    private val IMAGE_FALLBACK_EXTENSIONS = listOf("webp", "jpg", "png", "gif")
}

internal data class NhentaiMirrorMetadata(
    val taxonomy: List<PostTaxonomyTerm>,
    val authorName: String?,
) {
    val canonicalTags: List<String>
        get() = taxonomy.map(PostTaxonomyTerm::value)
}
