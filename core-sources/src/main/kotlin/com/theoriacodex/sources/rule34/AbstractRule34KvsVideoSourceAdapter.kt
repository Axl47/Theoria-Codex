package com.theoriacodex.sources.rule34

import com.google.gson.Gson
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpClient
import java.io.IOException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

abstract class AbstractRule34KvsVideoSourceAdapter(
    private val httpClient: SourceHttpClient,
    private val gson: Gson = Gson(),
) : SourceAdapter {
    abstract override val sourceKey: SourceKey
    protected abstract val baseUrl: String

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = false,
        supportsSortTop = false,
        supportsSortRandom = false,
        supportsExcludeTagsServerSide = false,
        supportsDateRangeServerSide = false,
        supportsMinScoreServerSide = false,
        requiresCredentials = false,
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        val queryText = compileRule34VideoSearchText(query)
        if (queryText.isBlank()) {
            val rssBody = request("$baseUrl/rss/")
            return parseLatestRss(rssBody)
        }

        val url = pageToken ?: initialSearchUrl(queryText)
        val body = request(url)
        val document = Jsoup.parse(body, baseUrl)
        return Page(
            items = parseSearchPage(document, query.includeTags),
            nextPageToken = nextPageToken(document, queryText),
        )
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
        if (limit <= 0) return emptyList()
        return runCatching {
            request("$baseUrl/tags_json.php?id=true&advanced_search=true")
        }.map { body ->
            parseTagSuggestionsFromSelect2(body, gson, "trending").take(limit)
        }.getOrDefault(emptyList())
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        val normalized = prefix.trim()
        if (normalized.isBlank() || limit <= 0) return emptyList()
        return runCatching {
            request("$baseUrl/tags_json.php?id=true&advanced_search=true&q=${encodePathSegment(normalized)}")
        }.map { body ->
            parseTagSuggestionsFromSelect2(body, gson, "tag").take(limit)
        }.getOrDefault(emptyList())
    }

    override suspend fun quickQuery(kind: QuickQueryKind): Query {
        return Query(
            mode = QueryMode.Source(sourceKey),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    override suspend fun resolvePost(id: PostId): Post? {
        if (id.source != sourceKey) return null
        val body = request("$baseUrl/video/${id.sourcePostId}/x/", allowNotFound = true)
        if (body.isBlank()) return null
        val document = Jsoup.parse(body, baseUrl)
        val config = parseRule34KvsConfig(document)
        val title = config.string("video_title")
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.title().trim()
        val tags = config.tags()
        val pageUrl = document.selectFirst("link[rel=canonical]")?.attr("abs:href")
            ?.takeIf(String::isNotBlank)
            ?: document.selectFirst("meta[property=og:url]")?.attr("content")
                ?.takeIf(String::isNotBlank)
            ?: "$baseUrl/video/${id.sourcePostId}/x/"
        val previewUrl = config.previewImageUrl()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val videoUrl = config.bestVideoUrl()
            ?: throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "${sourceKey.name} page missing playable video URL",
            )
        val videoRef = ImageRef(url = videoUrl, localPath = null, mime = "video/mp4")

        return Post(
            id = PostId(source = sourceKey, sourcePostId = id.sourcePostId),
            preview = ImageRef(url = previewUrl, localPath = null, mime = "image/jpeg"),
            full = videoRef,
            media = listOf(videoRef),
            pageUrl = pageUrl,
            width = null,
            height = null,
            canonicalTags = tags,
            rawTags = tags,
            authorName = config.string("video_models")
                ?.split(',')
                ?.map(String::trim)
                ?.firstOrNull(String::isNotBlank),
            createdAtEpochMs = null,
            title = title,
        )
    }

    protected abstract fun initialSearchUrl(queryText: String): String

    protected abstract fun nextPageToken(document: Document, queryText: String): String?

    protected abstract fun parseSearchPage(document: Document, includeTags: List<String>): List<Post>

    protected fun parseLatestRss(body: String): Page<Post> {
        val document = Jsoup.parse(body, baseUrl, Parser.xmlParser())
        val items = document.select("channel > item").mapNotNull { item ->
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            val sourcePostId = RULE34_VIDEO_ID_REGEX.find(link)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            val descriptionDoc = Jsoup.parse(item.selectFirst("description")?.text().orEmpty(), baseUrl)
            val imageUrl = descriptionDoc.selectFirst("img")?.attr("abs:src").orEmpty()
            val title = item.selectFirst("title")?.text()?.trim()?.takeIf(String::isNotBlank)
            Post(
                id = PostId(source = sourceKey, sourcePostId = sourcePostId),
                preview = ImageRef(url = imageUrl.ifBlank { null }, localPath = null, mime = "image/jpeg"),
                full = null,
                pageUrl = link,
                width = null,
                height = null,
                canonicalTags = emptyList(),
                rawTags = emptyList(),
                authorName = null,
                createdAtEpochMs = parseVideoSitePubDateEpochMs(item.selectFirst("pubDate")?.text()),
                title = title,
            )
        }
        return Page(items = items, nextPageToken = null)
    }

    protected fun searchPost(
        pageUrl: String,
        sourcePostId: String,
        title: String?,
        previewUrl: String?,
        includeTags: List<String>,
    ): Post {
        return Post(
            id = PostId(source = sourceKey, sourcePostId = sourcePostId),
            preview = ImageRef(url = previewUrl, localPath = null, mime = "image/jpeg"),
            full = null,
            media = emptyList(),
            pageUrl = pageUrl,
            width = null,
            height = null,
            canonicalTags = includeTags.map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase),
            rawTags = includeTags.map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase),
            authorName = null,
            createdAtEpochMs = null,
            title = title,
        )
    }

    protected suspend fun request(url: String, allowNotFound: Boolean = false): String {
        val response = try {
            httpClient.get(
                url = url,
                headers = RULE34_BROWSER_HEADERS + ("Referer" to "$baseUrl/"),
            )
        } catch (error: IOException) {
            rule34NetworkFailure(baseUrl, error)
        }

        if (allowNotFound && response.statusCode == 404) {
            return ""
        }
        if (response.statusCode == 404) return ""
        if (response.statusCode !in 200..299) {
            throw SourceAdapterException(
                reason = classifyRule34HttpFailure(response.statusCode),
                message = "$baseUrl request failed (${response.statusCode})",
            )
        }
        return response.body
    }
}

private val RULE34_VIDEO_ID_REGEX = Regex("""/video/(\d+)/""")
