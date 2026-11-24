package com.theoriacodex.sources.rule34

import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.adapter.AnimatedSearchTerm
import com.google.gson.Gson
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.common.asStringOrNull
import com.theoriacodex.sources.common.canonicalPostPage
import com.theoriacodex.sources.common.getChecked
import com.theoriacodex.sources.common.getCheckedOrNull
import com.theoriacodex.sources.common.normalizeBooruTag
import com.theoriacodex.sources.common.parseEpochMs
import com.theoriacodex.sources.common.parseHtml
import com.theoriacodex.sources.common.parseJsonArray
import com.theoriacodex.sources.common.parseXml
import com.theoriacodex.domain.encoding.percentEncode
import com.theoriacodex.sources.common.sourceCall
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.media.inferMimeFromUrl
import com.theoriacodex.sources.media.mimeFromFileExt
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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
        animatedTerm = AnimatedSearchTerm(SearchTerm("animated")),
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> = sourceCall {
        val normalizedQuery = query.includeTags
            .map(::normalizeBooruTag)
            .filter(String::isNotBlank)
            .joinToString(" ")

        val url = pageToken
            ?: if (normalizedQuery.isBlank()) {
                "$RULE34PAHEAL_BASE_URL/post/list"
            } else {
                "$RULE34PAHEAL_BASE_URL/rss/images/${normalizedQuery.percentEncode()}/1"
            }
        val body = httpClient.getChecked(PAHEAL_LABEL, url, headers = PAHEAL_HEADERS)
        if (body.trimStart().startsWith("<?xml") || body.contains("<rss")) {
            parseRssPage(body, limit = 40)
        } else {
            parseHtmlSearchPage(body)
        }
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = sourceCall {
        if (limit <= 0) return@sourceCall emptyList()
        runCatchingPreservingCancellation {
            val html = httpClient.getChecked(PAHEAL_LABEL, "$RULE34PAHEAL_BASE_URL/post/list", headers = PAHEAL_HEADERS)
            val doc = parseHtml(html, RULE34PAHEAL_BASE_URL)
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

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = sourceCall {
        val normalized = prefix.trim()
        if (normalized.isBlank() || limit <= 0) return@sourceCall emptyList()
        val response = httpClient.getChecked(PAHEAL_LABEL, "$RULE34PAHEAL_BASE_URL/browser_search/${normalized.percentEncode()}")
        val root = parseJsonArray(response, gson, "rule34.paheal.net browser search")
        val values = root.get(1)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
        values.mapNotNull { item ->
            val text = item.asStringOrNull()?.trim().orEmpty()
            if (text.isBlank()) null else TagSuggestion(text = text, type = "tag", count = null)
        }.take(limit)
    }

    override suspend fun resolvePost(id: PostId): Post? = sourceCall {
        if (id.source != SourceKey.RULE34PAHEAL) return@sourceCall null
        val html = httpClient.getCheckedOrNull(PAHEAL_LABEL, "$RULE34PAHEAL_BASE_URL/post/view/${id.sourcePostId}", PAHEAL_HEADERS)
            ?: return@sourceCall null
        val doc = parseHtml(html, RULE34PAHEAL_BASE_URL)
        parsePostDocument(doc, id.sourcePostId)
    }

    private fun parseRssPage(body: String, limit: Int): Page<Post> {
        val document = parseXml(body, RULE34PAHEAL_BASE_URL)
        val items = document.select("channel > item")
            .take(limit)
            .mapNotNull { item ->
                val link = item.selectFirst("link")?.text()?.trim().orEmpty()
                val sourcePostId = link.substringAfterLast('/').substringBefore('#').takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val descriptionDoc = parseHtml(item.selectFirst("description")?.text().orEmpty(), RULE34PAHEAL_BASE_URL)
                val thumb = descriptionDoc.selectFirst("div.shm-thumb.thumb")
                val image = descriptionDoc.selectFirst("img")
                val previewUrl = item.selectFirst("media|thumbnail")?.attr("url").orEmpty().ifBlank {
                    image?.attr("src").orEmpty()
                }
                val fullUrl = item.selectFirst("media|content")?.attr("url").orEmpty().ifBlank { descriptionDoc.fileOnlyUrl() }
                val mime = thumb?.pahealFileMime() ?: inferMimeFromUrl(fullUrl)
                val (width, height) = pahealDimensions(image?.attr("title").orEmpty())
                pahealPost(
                    sourcePostId = sourcePostId,
                    preview = pahealThumbnail(previewUrl),
                    full = fullUrl.ifBlank { null }?.let { url -> ImageRef(url = url, localPath = null, mime = mime) },
                    pageUrl = link,
                    tags = thumb?.pahealTags().orEmpty(),
                    title = item.selectFirst("title")?.text()
                        ?.substringAfter(" - ", "")
                        ?.trim()
                        ?.takeIf(String::isNotBlank),
                    width = width,
                    height = height,
                    createdAtEpochMs = parseRfc1123EpochMs(item.selectFirst("pubDate")?.text()),
                )
            }
        val next = document.selectFirst("channel > atom|link[rel=next]")?.attr("abs:href")?.takeIf(String::isNotBlank)
        return canonicalPostPage(items = items, nextPageToken = next)
    }

    private fun parseHtmlSearchPage(body: String): Page<Post> {
        val document = parseHtml(body, RULE34PAHEAL_BASE_URL)
        val posts = document.select("div.shm-thumb.thumb[data-post-id]").mapNotNull { thumb ->
            val sourcePostId = thumb.attr("data-post-id").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val link = thumb.selectFirst("a.shm-thumb-link")?.attr("abs:href").orEmpty()
            val image = thumb.selectFirst("img")
            val fileMime = thumb.pahealFileMime()
            val titleAttr = image?.attr("title").orEmpty()
            val lines = titleAttr.lines().map(String::trim).filter(String::isNotBlank)
            val (width, height) = pahealDimensions(titleAttr)
            pahealPost(
                sourcePostId = sourcePostId,
                preview = pahealThumbnail(image?.attr("abs:src").orEmpty()),
                full = thumb.fileOnlyUrl().ifBlank { null }?.let { url ->
                    ImageRef(url = url, localPath = null, mime = fileMime ?: inferMimeFromUrl(url))
                },
                pageUrl = link,
                tags = thumb.pahealTags(),
                title = lines.firstOrNull()?.substringBefore("//")?.trim(),
                width = width,
                height = height,
                createdAtEpochMs = parsePahealThumbDateEpochMs(lines.lastOrNull()),
            )
        }
        val next = document.select("section#paginator a, section#Navigationleft a")
            .firstOrNull { link -> link.text().trim().equals("Next", ignoreCase = true) }
            ?.attr("abs:href")
            ?.takeIf(String::isNotBlank)
        return canonicalPostPage(items = posts, nextPageToken = next)
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
            pahealPost(
                sourcePostId = sourcePostId,
                preview = ImageRef(url = poster, localPath = null, mime = inferMimeFromUrl(poster)),
                full = videoRef,
                media = listOf(videoRef),
                pageUrl = pageUrl,
                tags = tags,
                title = title,
                width = RULE34PAHEAL_STYLE_WIDTH_REGEX.find(main.attr("style"))?.groupValues?.getOrNull(1)?.toIntOrNull(),
                height = RULE34PAHEAL_STYLE_HEIGHT_REGEX.find(main.attr("style"))?.groupValues?.getOrNull(1)?.toIntOrNull(),
            )
        } else {
            val imageUrl = main.attr("abs:src").ifBlank { main.attr("src") }.ifBlank { return null }
            val mime = main.pahealFileMime() ?: inferMimeFromUrl(imageUrl)
            val imageRef = ImageRef(url = imageUrl, localPath = null, mime = mime)
            pahealPost(
                sourcePostId = sourcePostId,
                preview = imageRef,
                full = imageRef,
                pageUrl = pageUrl,
                tags = tags,
                title = title,
                width = main.attr("data-width").toIntOrNull(),
                height = main.attr("data-height").toIntOrNull(),
            )
        }
    }
}

private fun pahealPost(
    sourcePostId: String,
    preview: ImageRef,
    full: ImageRef?,
    pageUrl: String,
    tags: List<String>,
    title: String?,
    width: Int?,
    height: Int?,
    media: List<ImageRef> = emptyList(),
    createdAtEpochMs: Long? = null,
): Post = Post(
    id = PostId(source = SourceKey.RULE34PAHEAL, sourcePostId = sourcePostId),
    preview = preview,
    full = full,
    media = media,
    pageUrl = pageUrl.ifBlank { "$RULE34PAHEAL_BASE_URL/post/view/$sourcePostId" },
    width = width,
    height = height,
    canonicalTags = tags,
    rawTags = tags,
    authorName = tags.firstOrNull(),
    createdAtEpochMs = createdAtEpochMs,
    title = title,
)

private fun pahealThumbnail(url: String) = ImageRef(url = url.ifBlank { null }, localPath = null, mime = PAHEAL_THUMBNAIL_MIME)

private fun Element.pahealTags(): List<String> = attr("data-tags").split(' ').map(String::trim).filter(String::isNotBlank)

private fun Element.fileOnlyUrl(): String = select("a")
    .firstOrNull { anchor -> anchor.text().contains("file only", ignoreCase = true) }
    ?.attr("abs:href")
    .orEmpty()

private fun pahealDimensions(title: String): Pair<Int?, Int?> {
    val match = RULE34PAHEAL_DIMENSION_REGEX.find(title)
    return match?.groupValues?.getOrNull(1)?.toIntOrNull() to match?.groupValues?.getOrNull(2)?.toIntOrNull()
}

private val PAHEAL_THUMB_DATE = DateTimeFormatter.ofPattern("MMMM d, uuuu; HH:mm", Locale.US)

private fun parsePahealThumbDateEpochMs(raw: String?): Long? =
    parseEpochMs(raw) { LocalDateTime.parse(it, PAHEAL_THUMB_DATE).toInstant(ZoneOffset.UTC) }

private fun Element.pahealFileMime(): String? =
    attr("data-mime").substringBefore(';').trim().ifBlank { null } ?: mimeFromFileExt(attr("data-ext"))

private const val PAHEAL_THUMBNAIL_MIME = "image/jpeg"
private val RULE34PAHEAL_DIMENSION_REGEX = Regex("""(\d+)\s*x\s*(\d+)""")
private val RULE34PAHEAL_STYLE_WIDTH_REGEX = Regex("""width:\s*(\d+)px""")
private val RULE34PAHEAL_STYLE_HEIGHT_REGEX = Regex("""height:\s*(\d+)px""")
private const val RULE34PAHEAL_BASE_URL = "https://rule34.paheal.net"
private const val PAHEAL_LABEL = "rule34.paheal.net"
private val PAHEAL_HEADERS = RULE34_BROWSER_HEADERS + ("Referer" to "$RULE34PAHEAL_BASE_URL/")
