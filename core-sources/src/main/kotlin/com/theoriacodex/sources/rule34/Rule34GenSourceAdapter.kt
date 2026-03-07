package com.theoriacodex.sources.rule34

import com.google.gson.Gson
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpClient
import org.jsoup.nodes.Document

class Rule34GenSourceAdapter(
    httpClient: SourceHttpClient,
    gson: Gson = Gson(),
) : AbstractRule34KvsVideoSourceAdapter(
    httpClient = httpClient,
    gson = gson,
) {
    override val sourceKey: SourceKey = SourceKey.RULE34GEN
    override val baseUrl: String = RULE34GEN_BASE_URL

    override fun initialSearchUrl(queryText: String): String {
        return "$baseUrl/search/${encodePathSegment(queryText)}/"
    }

    override fun nextPageToken(document: Document, queryText: String): String? {
        return document.selectFirst("[data-block-next]")?.attr("data-block-next")?.trim()?.takeIf(String::isNotBlank)
    }

    override fun parseSearchPage(document: Document, includeTags: List<String>) = document
        .select("div.cards__item a.card[href]")
        .mapNotNull { anchor ->
            val pageUrl = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            val sourcePostId = RULE34GEN_ID_REGEX.find(pageUrl)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
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

private val RULE34GEN_ID_REGEX = Regex("""/video/(\d+)/""")
private const val RULE34GEN_BASE_URL = "https://rule34gen.com"
