package com.theoriacodex.sources.rule34

import com.google.gson.Gson
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpClient
import org.jsoup.nodes.Document

class Rule34VideoSourceAdapter(
    httpClient: SourceHttpClient,
    gson: Gson = Gson(),
) : AbstractRule34KvsVideoSourceAdapter(
    httpClient = httpClient,
    gson = gson,
) {
    override val sourceKey: SourceKey = SourceKey.RULE34VIDEO
    override val baseUrl: String = RULE34VIDEO_BASE_URL

    override fun initialSearchUrl(queryText: String): String {
        return "$baseUrl/search/${encodePathSegment(queryText)}/"
    }

    override fun nextPageToken(document: Document, queryText: String): String? {
        val nextLink = document.selectFirst("div.pager.next a[data-parameters]") ?: return null
        val parameters = decodeDataParameters(nextLink.attr("data-parameters")).takeIf(String::isNotBlank) ?: return null
        val blockId = nextLink.attr("data-block-id").ifBlank { "custom_list_videos_videos_list_search" }
        return "${initialSearchUrl(queryText)}?mode=async&function=get_block&block_id=$blockId&$parameters"
    }

    override fun parseSearchPage(document: Document, includeTags: List<String>) = document
        .select("div.item.thumb a.th[href]")
        .mapNotNull { anchor ->
            val pageUrl = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            val sourcePostId = RULE34VIDEO_ID_REGEX.find(pageUrl)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            val image = anchor.selectFirst("img")
            searchPost(
                pageUrl = pageUrl,
                sourcePostId = sourcePostId,
                title = anchor.attr("title").trim().ifBlank { null },
                previewUrl = image?.attr("data-original")?.ifBlank { image.attr("src") },
                includeTags = includeTags,
            )
        }
}

private fun decodeDataParameters(raw: String): String {
    return raw
        .split(';')
        .filter { it.isNotBlank() }
        .joinToString("&") { segment ->
            val key = segment.substringBefore(':').trim()
            val value = segment.substringAfter(':', "").trim()
            if ('+' in key) {
                key.split('+').joinToString("&") { nestedKey ->
                    "${nestedKey.trim()}=${encodePathSegment(value)}"
                }
            } else {
                "$key=${encodePathSegment(value)}"
            }
        }
}

private val RULE34VIDEO_ID_REGEX = Regex("""/video/(\d+)/""")
private const val RULE34VIDEO_BASE_URL = "https://rule34video.com"
