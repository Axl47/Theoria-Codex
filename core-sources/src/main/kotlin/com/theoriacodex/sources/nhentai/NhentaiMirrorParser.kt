package com.theoriacodex.sources.nhentai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import org.jsoup.Jsoup

internal object NhentaiMirrorParser {
    fun parseGalleryMetadata(body: String): NhentaiMirrorMetadata? {
        val document = Jsoup.parse(body)
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

    fun parseSearchPage(body: String): JsonObject {
        val posts = JsonArray()
        body.lineSequence().mapNotNull(::parseSearchPost).forEach(posts::add)
        return JsonObject().apply {
            add("result", posts)
            addProperty("num_pages", parsePageCount(body))
            addProperty("per_page", posts.size().coerceAtLeast(1))
        }
    }

    fun parseTitle(body: String): String? {
        return body.lineSequence()
            .firstOrNull { line -> line.startsWith("Title: ") }
            ?.removePrefix("Title: ")
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun parseSearchPost(line: String): JsonObject? {
        val galleryId = NHENTAI_MIRROR_GALLERY_LINK_REGEX.find(line)?.groupValues?.getOrNull(1)
            ?: return null
        val previewMatch = NHENTAI_MIRROR_THUMB_REGEX.find(line) ?: return null
        val previewUrl = previewMatch.groupValues[1]
        val mediaId = previewMatch.groupValues[2]
        val thumbExt = imageExtension(previewUrl.substringAfterLast('.').substringBefore('?'))

        return JsonObject().apply {
            addProperty("id", galleryId)
            addProperty("media_id", mediaId)
            add("title", JsonObject().apply { addProperty("pretty", parseSearchTitle(line, previewUrl)) })
            add("images", JsonObject().apply {
                add("thumbnail", JsonObject().apply { addProperty("t", thumbExt) })
                add("cover", JsonObject().apply { addProperty("t", thumbExt) })
                add("pages", JsonArray())
            })
            add("tags", JsonArray())
            addProperty("mirror_sparse", true)
        }
    }

    private fun parseSearchTitle(line: String, previewUrl: String): String? {
        val beforePreview = line.substringBefore("]($previewUrl)", missingDelimiterValue = "")
        val titleStart = beforePreview.indexOf(": ").takeIf { it >= 0 }?.plus(2) ?: return null
        val titleEnd = beforePreview.lastIndexOf("](").takeIf { it > titleStart } ?: beforePreview.length
        return beforePreview.substring(titleStart, titleEnd).trim().takeIf(String::isNotBlank)
    }

    private fun parsePageCount(body: String): Int {
        val linkedMaxPage = NHENTAI_MIRROR_PAGE_LINK_REGEX.findAll(body)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
        if (linkedMaxPage != null) return linkedMaxPage.coerceAtLeast(1)

        val totalResults = NHENTAI_MIRROR_RESULT_COUNT_REGEX.find(body)
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
}

internal data class NhentaiMirrorMetadata(
    val taxonomy: List<PostTaxonomyTerm>,
    val authorName: String?,
) {
    val canonicalTags: List<String>
        get() = taxonomy.map(PostTaxonomyTerm::value)
}
