package com.theoriacodex.sources.rule34

import com.google.gson.Gson
import com.google.gson.JsonArray
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
import com.theoriacodex.sources.media.inferMimeFromUrl
import com.theoriacodex.sources.media.mimeFromFileExt
import java.io.IOException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

class Rule34PahealSourceAdapter(
    private val httpClient: SourceHttpClient,
    private val gson: Gson = Gson(),
) : SourceAdapter {
    override val sourceKey: SourceKey = SourceKey.RULE34PAHEAL

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
        val normalizedQuery = query.includeTags
            .map(::normalizeRule34BooruTag)
            .filter(String::isNotBlank)
            .joinToString(" ")

        val url = pageToken
            ?: if (normalizedQuery.isBlank()) {
                "$RULE34PAHEAL_BASE_URL/post/list"
            } else {
                "$RULE34PAHEAL_BASE_URL/rss/images/${encodePathSegment(normalizedQuery)}/1"
            }
        val body = request(url) ?: return Page(items = emptyList(), nextPageToken = null)
        return if (body.trimStart().startsWith("<?xml") || body.contains("<rss")) {
            parseRssPage(body, limit = 40)
        } else {
            parseHtmlSearchPage(body)
        }
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
        if (limit <= 0) return emptyList()
        return runCatching {
            val html = request("$RULE34PAHEAL_BASE_URL/post/list") ?: return emptyList()
            val doc = Jsoup.parse(html, RULE34PAHEAL_BASE_URL)
            val counts = linkedMapOf<String, Int>()
            doc.select("div.shm-thumb.thumb[data-tags]").forEach { thumb ->
                thumb.attr("data-tags")
                    .split(' ')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .forEach { tag ->
                        counts[tag] = (counts[tag] ?: 0) + 1
                    }
            }
            counts.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { (tag, count) ->
                    TagSuggestion(text = tag, type = "trending", count = count)
                }
        }.getOrDefault(emptyList())
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        val normalized = prefix.trim()
        if (normalized.isBlank() || limit <= 0) return emptyList()
        val response = request(
            "$RULE34PAHEAL_BASE_URL/browser_search/${encodePathSegment(normalized)}",
            headers = emptyMap(),
        ) ?: return emptyList()
        val root = parseJsonArray(response, gson, "rule34.paheal.net browser search")
        val values = root.get(1)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        return values.mapNotNull { item ->
            val text = runCatching { item.asString.trim() }.getOrDefault("")
            if (text.isBlank()) null else TagSuggestion(text = text, type = "tag", count = null)
        }.take(limit)
    }

    override suspend fun quickQuery(kind: QuickQueryKind): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.RULE34PAHEAL),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    override suspend fun resolvePost(id: PostId): Post? {
        if (id.source != SourceKey.RULE34PAHEAL) return null
        val html = request("$RULE34PAHEAL_BASE_URL/post/view/${id.sourcePostId}", allowNotFound = true) ?: return null
        val doc = Jsoup.parse(html, RULE34PAHEAL_BASE_URL)
        return parsePostDocument(doc, id.sourcePostId)
    }

    private suspend fun request(
        url: String,
        allowNotFound: Boolean = false,
        headers: Map<String, String> = RULE34_BROWSER_HEADERS + ("Referer" to "$RULE34PAHEAL_BASE_URL/"),
    ): String? {
        val response = try {
            httpClient.get(url = url, headers = headers)
        } catch (error: IOException) {
            rule34NetworkFailure("rule34.paheal.net", error)
        }

        if (allowNotFound && response.statusCode == 404) {
            return null
        }
        if (response.statusCode !in 200..299) {
            throw SourceAdapterException(
                reason = classifyRule34HttpFailure(response.statusCode),
                message = "rule34.paheal.net request failed (${response.statusCode})",
            )
        }
        return response.body
    }

    private fun parseRssPage(body: String, limit: Int): Page<Post> {
        val document = Jsoup.parse(body, RULE34PAHEAL_BASE_URL, Parser.xmlParser())
        val items = document.select("channel > item")
            .take(limit)
            .mapNotNull { item ->
                val link = item.selectFirst("link")?.text()?.trim().orEmpty()
                val sourcePostId = link.substringAfterLast('/').substringBefore('#').takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val descriptionDoc = Jsoup.parse(item.selectFirst("description")?.text().orEmpty(), RULE34PAHEAL_BASE_URL)
                val thumb = descriptionDoc.selectFirst("div.shm-thumb.thumb")
                val image = descriptionDoc.selectFirst("img")
                val previewUrl = item.selectFirst("media|thumbnail")?.attr("url").orEmpty().ifBlank {
                    image?.attr("src").orEmpty()
                }
                val fullUrl = item.selectFirst("media|content")?.attr("url").orEmpty().ifBlank {
                    descriptionDoc.select("a")
                        .firstOrNull { anchor -> anchor.text().contains("file only", ignoreCase = true) }
                        ?.attr("abs:href")
                        .orEmpty()
                }
                val ext = thumb?.attr("data-ext").orEmpty()
                val tags = thumb?.attr("data-tags")
                    ?.split(' ')
                    ?.map(String::trim)
                    ?.filter(String::isNotBlank)
                    .orEmpty()
                val title = item.selectFirst("title")?.text()
                    ?.substringAfter(" - ", "")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                val mime = mimeFromFileExt(ext) ?: inferMimeFromUrl(fullUrl)
                val previewMime = mimeFromFileExt(ext) ?: inferMimeFromUrl(previewUrl) ?: mime
                val sizeMatch = RULE34PAHEAL_DIMENSION_REGEX.find(image?.attr("title").orEmpty())
                val width = sizeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                val height = sizeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

                Post(
                    id = PostId(source = SourceKey.RULE34PAHEAL, sourcePostId = sourcePostId),
                    preview = ImageRef(url = previewUrl.ifBlank { null }, localPath = null, mime = previewMime),
                    full = fullUrl.ifBlank { null }?.let { url ->
                        ImageRef(url = url, localPath = null, mime = mime)
                    },
                    pageUrl = link.ifBlank { "$RULE34PAHEAL_BASE_URL/post/view/$sourcePostId" },
                    width = width,
                    height = height,
                    canonicalTags = tags,
                    rawTags = tags,
                    authorName = tags.firstOrNull(),
                    createdAtEpochMs = parseRfc1123EpochMs(item.selectFirst("pubDate")?.text()),
                    title = title,
                )
            }
        val next = document.selectFirst("channel > atom|link[rel=next]")?.attr("abs:href")?.takeIf(String::isNotBlank)
        return Page(items = items, nextPageToken = next)
    }

    private fun parseHtmlSearchPage(body: String): Page<Post> {
        val document = Jsoup.parse(body, RULE34PAHEAL_BASE_URL)
        val posts = document.select("div.shm-thumb.thumb[data-post-id]").mapNotNull { thumb ->
            val sourcePostId = thumb.attr("data-post-id").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val link = thumb.selectFirst("a.shm-thumb-link")?.attr("abs:href").orEmpty()
            val image = thumb.selectFirst("img")
            val previewUrl = image?.attr("abs:src").orEmpty()
            val fullUrl = thumb.select("a")
                .firstOrNull { anchor -> anchor.text().contains("file only", ignoreCase = true) }
                ?.attr("abs:href")
                .orEmpty()
            val ext = thumb.attr("data-ext")
            val tags = thumb.attr("data-tags")
                .split(' ')
                .map(String::trim)
                .filter(String::isNotBlank)
            val titleAttr = image?.attr("title").orEmpty()
            val lines = titleAttr.lines().map(String::trim).filter(String::isNotBlank)
            val sizeMatch = RULE34PAHEAL_DIMENSION_REGEX.find(titleAttr)
            val width = sizeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            val height = sizeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

            Post(
                id = PostId(source = SourceKey.RULE34PAHEAL, sourcePostId = sourcePostId),
                preview = ImageRef(
                    url = previewUrl.ifBlank { null },
                    localPath = null,
                    mime = mimeFromFileExt(ext) ?: inferMimeFromUrl(previewUrl),
                ),
                full = fullUrl.ifBlank { null }?.let { url ->
                    ImageRef(url = url, localPath = null, mime = mimeFromFileExt(ext) ?: inferMimeFromUrl(url))
                },
                pageUrl = link.ifBlank { "$RULE34PAHEAL_BASE_URL/post/view/$sourcePostId" },
                width = width,
                height = height,
                canonicalTags = tags,
                rawTags = tags,
                authorName = tags.firstOrNull(),
                createdAtEpochMs = parsePahealThumbDateEpochMs(lines.lastOrNull()),
                title = lines.firstOrNull()?.substringBefore("//")?.trim(),
            )
        }
        val next = document.select("section#paginator a, section#Navigationleft a")
            .firstOrNull { link -> link.text().trim().equals("Next", ignoreCase = true) }
            ?.attr("abs:href")
            ?.takeIf(String::isNotBlank)
        return Page(items = posts, nextPageToken = next)
    }

    private fun parsePostDocument(document: Document, sourcePostId: String): Post? {
        val main = document.selectFirst("#main_image") ?: return null
        val tags = document.select("a.tag_name")
            .mapNotNull { element -> element.text().trim().takeIf(String::isNotBlank) }
            .distinctBy(String::lowercase)
        val pageUrl = document.location()
            .takeIf { location -> location.isNotBlank() && location != RULE34PAHEAL_BASE_URL }
            ?: "$RULE34PAHEAL_BASE_URL/post/view/$sourcePostId"
        val title = document.title()
            .substringBefore(" - Rule 34")
            .trim()
            .takeIf(String::isNotBlank)

        return if (main.tagName().equals("video", ignoreCase = true)) {
            val source = main.selectFirst("source[src]") ?: return null
            val videoUrl = source.attr("abs:src").ifBlank { source.attr("src") }
            val poster = main.attr("poster").ifBlank { null }
            val mime = source.attr("type").ifBlank { inferMimeFromUrl(videoUrl) ?: "video/mp4" }
            val videoRef = ImageRef(url = videoUrl, localPath = null, mime = mime)
            Post(
                id = PostId(source = SourceKey.RULE34PAHEAL, sourcePostId = sourcePostId),
                preview = ImageRef(url = poster, localPath = null, mime = inferMimeFromUrl(poster)),
                full = videoRef,
                media = listOf(videoRef),
                pageUrl = pageUrl,
                width = RULE34PAHEAL_STYLE_WIDTH_REGEX.find(main.attr("style"))?.groupValues?.getOrNull(1)?.toIntOrNull(),
                height = RULE34PAHEAL_STYLE_HEIGHT_REGEX.find(main.attr("style"))?.groupValues?.getOrNull(1)?.toIntOrNull(),
                canonicalTags = tags,
                rawTags = tags,
                authorName = tags.firstOrNull(),
                createdAtEpochMs = null,
                title = title,
            )
        } else {
            val imageUrl = main.attr("abs:src").ifBlank { main.attr("src") }.ifBlank { return null }
            val mime = main.attr("data-mime").ifBlank { inferMimeFromUrl(imageUrl) }
            val imageRef = ImageRef(url = imageUrl, localPath = null, mime = mime)
            Post(
                id = PostId(source = SourceKey.RULE34PAHEAL, sourcePostId = sourcePostId),
                preview = imageRef,
                full = imageRef,
                pageUrl = pageUrl,
                width = main.attr("data-width").toIntOrNull(),
                height = main.attr("data-height").toIntOrNull(),
                canonicalTags = tags,
                rawTags = tags,
                authorName = tags.firstOrNull(),
                createdAtEpochMs = null,
                title = title,
            )
        }
    }
}

private val RULE34PAHEAL_DIMENSION_REGEX = Regex("""(\d+)\s*x\s*(\d+)""")
private val RULE34PAHEAL_STYLE_WIDTH_REGEX = Regex("""width:\s*(\d+)px""")
private val RULE34PAHEAL_STYLE_HEIGHT_REGEX = Regex("""height:\s*(\d+)px""")
private const val RULE34PAHEAL_BASE_URL = "https://rule34.paheal.net"
