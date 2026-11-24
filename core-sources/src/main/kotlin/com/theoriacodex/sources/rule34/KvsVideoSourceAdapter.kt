package com.theoriacodex.sources.rule34

import com.google.gson.Gson
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SearchMetadataSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.common.autocompleteTagCounts
import com.theoriacodex.sources.common.canonicalPostPage
import com.theoriacodex.sources.common.elementsOrEmpty
import com.theoriacodex.sources.common.getCheckedOrNull
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.optionalJsonArray
import com.theoriacodex.sources.common.parseEpochMs
import com.theoriacodex.sources.common.parseHtml
import com.theoriacodex.sources.common.parseJsonObject
import com.theoriacodex.sources.common.parseXml
import com.theoriacodex.domain.encoding.percentEncode
import com.theoriacodex.sources.common.sourceCall
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.http.SourceHttpClient
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import org.jsoup.nodes.Document

enum class KvsPaging {
    DATA_BLOCK_NEXT,

    ASYNC_PAGER,
}

enum class KvsLatest {
    RSS,

    LISTING,
}

enum class KvsVideoSite(
    val source: SourceKey,
    val baseUrl: String,
    val cardSelector: String,
    val imgPreviewFallback: Boolean,
    val paging: KvsPaging,
    val latest: KvsLatest,
) {
    RULE34VIDEO(
        source = SourceKey.RULE34VIDEO,
        baseUrl = "https://rule34video.com",
        cardSelector = "div.item.thumb a.th[href]",
        imgPreviewFallback = false,
        paging = KvsPaging.ASYNC_PAGER,
        latest = KvsLatest.RSS,
    ),
    RULE34GEN(
        source = SourceKey.RULE34GEN,
        baseUrl = "https://rule34gen.com",
        cardSelector = "div.item.thumb a.th[href], div.cards__item a.card[href]",
        imgPreviewFallback = true,
        paging = KvsPaging.DATA_BLOCK_NEXT,
        latest = KvsLatest.LISTING,
    ),
}

class KvsVideoSourceAdapter(
    private val site: KvsVideoSite,
    private val httpClient: SourceHttpClient,
    private val gson: Gson = Gson(),
    private val callContext: CoroutineContext = Dispatchers.Default,
) : SourceAdapter, SearchMetadataSourceAdapter, TagCountLookupSourceAdapter {
    override val sourceKey: SourceKey = site.source
    private val baseUrl: String = site.baseUrl

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = false,
        supportsSortTop = false,
        supportsSortRandom = false,
        supportsExcludeTagsServerSide = false,
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> = sourceCall(callContext) {
        val queryText = compileRule34VideoSearchText(query)
        if (queryText.isBlank()) return@sourceCall latestPage()

        val url = pageToken ?: searchUrl(queryText)
        val body = request(url)
        val document = parseHtml(body, baseUrl)
        canonicalPostPage(
            items = parseSearchPage(document),
            nextPageToken = nextPageToken(document, queryText),
        )
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = sourceCall(callContext) {
        if (limit <= 0) return@sourceCall emptyList()
        runCatchingPreservingCancellation {
            request("$baseUrl/tags_json.php?id=true&advanced_search=true")
        }.map { body ->
            parseTagSuggestionsFromSelect2(body, gson, "trending").take(limit).ifEmpty {
                fallbackTrendingTags(limit)
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun fallbackTrendingTags(limit: Int): List<TagSuggestion> {
        val prefixes = listOf("anim", "anime", "large", "girl")
        val suggestions = linkedMapOf<String, TagSuggestion>()
        prefixes.forEach { prefix ->
            if (suggestions.size >= limit) return@forEach
            runCatchingPreservingCancellation {
                request("$baseUrl/tags_json.php?id=true&advanced_search=true&q=${prefix.percentEncode()}")
            }.getOrNull()
                ?.let { body -> parseTagSuggestionsFromSelect2(body, gson, "trending") }
                ?.forEach { suggestion ->
                    if (suggestions.size < limit) {
                        suggestions.putIfAbsent(suggestion.text.lowercase(), suggestion.copy(type = "trending"))
                    }
                }
        }
        return suggestions.values.take(limit)
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = sourceCall(callContext) {
        val normalized = prefix.trim()
        if (normalized.isBlank() || limit <= 0) return@sourceCall emptyList()
        runCatchingPreservingCancellation {
            request("$baseUrl/tags_json.php?id=true&advanced_search=true&q=${normalized.percentEncode()}")
        }.map { body ->
            parseTagSuggestionsFromSelect2(body, gson, "tag").take(limit)
        }.getOrDefault(emptyList())
    }

    override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> = sourceCall(callContext) {
        autocompleteTagCounts(sourceKey, tags, ::autocompleteTags)
    }

    override suspend fun resolvePost(id: PostId): Post? = sourceCall(callContext) {
        if (id.source != sourceKey) return@sourceCall null
        val body = request("$baseUrl/video/${id.sourcePostId}/x/")
        if (body.isBlank()) return@sourceCall null
        val document = parseHtml(body, baseUrl)
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
                message = "${sourceKey.id} page missing playable video URL",
            )
        val videoRef = ImageRef(url = videoUrl, localPath = null, mime = "video/mp4", videoVariants = config.videoVariants())

        Post(
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
            durationMs = config.durationMs(),
        )
    }

    override suspend fun resolveSearchMetadata(post: Post): Post? = sourceCall(callContext) {
        if (post.full != null) return@sourceCall post
        resolvePost(post.id)?.let { resolved ->
            resolved.copy(
                preview = resolved.preview.takeIf { it.url != null } ?: post.preview,
                title = resolved.title ?: post.title,
                createdAtEpochMs = resolved.createdAtEpochMs ?: post.createdAtEpochMs,
                durationMs = resolved.durationMs ?: post.durationMs,
            )
        }
    }

    private suspend fun latestPage(): Page<Post> = when (site.latest) {
        KvsLatest.RSS -> parseLatestRss(request("$baseUrl/rss/"))
        KvsLatest.LISTING -> canonicalPostPage(
            items = parseSearchPage(parseHtml(request("$baseUrl/latest-updates/"), baseUrl)),
            nextPageToken = null,
        )
    }

    private fun searchUrl(queryText: String): String = "$baseUrl/search/${queryText.percentEncode()}/"

    private fun nextPageToken(document: Document, queryText: String): String? = when (site.paging) {
        KvsPaging.DATA_BLOCK_NEXT ->
            document.selectFirst("[data-block-next]")?.attr("data-block-next")?.trim()?.takeIf(String::isNotBlank)
        KvsPaging.ASYNC_PAGER -> asyncPagerToken(document, queryText)
    }

    private fun asyncPagerToken(document: Document, queryText: String): String? {
        val nextLink = document.selectFirst("div.pager.next a[data-parameters]") ?: return null
        val parameters = decodeDataParameters(nextLink.attr("data-parameters")).takeIf(String::isNotBlank) ?: return null
        val blockId = nextLink.attr("data-block-id").ifBlank { "custom_list_videos_videos_list_search" }
        return "${searchUrl(queryText)}?mode=async&function=get_block&block_id=$blockId&$parameters"
    }

    private fun parseSearchPage(document: Document): List<Post> = document
        .select(site.cardSelector)
        .mapNotNull { anchor ->
            val pageUrl = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            val sourcePostId = KVS_VIDEO_ID_REGEX.find(pageUrl)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            val image = anchor.selectFirst("img")
            val previewVideoUrl = anchor.selectFirst("div[data-preview]")?.attr("data-preview")?.trim()?.ifBlank { null }
                ?: image?.attr("data-preview")?.ifBlank { null }?.takeIf { site.imgPreviewFallback }
            searchPost(
                pageUrl = pageUrl,
                sourcePostId = sourcePostId,
                title = anchor.attr("title").trim().ifBlank { null },
                previewUrl = image?.kvsThumbnailUrl(),
                previewVideoUrl = previewVideoUrl,
                durationMs = parseRule34DurationMs(
                    anchor.selectFirst(".duration, .time, time, [data-duration]")?.let { element ->
                        element.attr("data-duration").ifBlank { element.text() }
                    }
                ),
            )
        }

    private fun parseLatestRss(body: String): Page<Post> {
        val document = parseXml(body, baseUrl)
        val items = document.select("channel > item").mapNotNull { item ->
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            val sourcePostId = KVS_VIDEO_ID_REGEX.find(link)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            val descriptionDoc = parseHtml(item.selectFirst("description")?.text().orEmpty(), baseUrl)
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
        return canonicalPostPage(items = items, nextPageToken = null)
    }

    private fun searchPost(
        pageUrl: String,
        sourcePostId: String,
        title: String?,
        previewUrl: String?,
        previewVideoUrl: String?,
        durationMs: Long? = null,
    ): Post {
        return Post(
            id = PostId(source = sourceKey, sourcePostId = sourcePostId),
            preview = ImageRef(url = previewUrl, localPath = null, mime = "image/jpeg"),
            full = null,
            media = previewVideoUrl?.let { url ->
                listOf(ImageRef(url = url, localPath = null, mime = "video/mp4"))
            }.orEmpty(),
            pageUrl = pageUrl,
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = title,
            durationMs = durationMs,
        )
    }

    private suspend fun request(url: String): String =
        httpClient.getCheckedOrNull(baseUrl, url, RULE34_BROWSER_HEADERS + ("Referer" to "$baseUrl/")).orEmpty()
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
                    "${nestedKey.trim()}=${value.percentEncode()}"
                }
            } else {
                "$key=${value.percentEncode()}"
            }
        }
}

private val KVS_VIDEO_ID_REGEX = Regex("""/video/(\d+)/""")

private fun compileRule34VideoSearchText(query: Query): String {
    val includeTags = query.includeTags
        .map(String::trim)
        .filter(String::isNotBlank)
    return includeTags.joinToString(" ").trim()
}

private fun parseTagSuggestionsFromSelect2(
    body: String,
    gson: Gson,
    defaultType: String,
): List<TagSuggestion> {
    val root = parseJsonObject(body, gson, "Rule34 video tag lookup")
    return root.optionalJsonArray("items").elementsOrEmpty().mapNotNull { item ->
        val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val text = obj.stringValue("title")?.trim().orEmpty()
        if (text.isBlank()) return@mapNotNull null
        TagSuggestion(
            text = text,
            type = defaultType,
            count = obj.stringValue("total")?.toIntOrNull() ?: obj.intValue("total"),
        )
    }
}

private val KVS_PUB_DATE = DateTimeFormatter.ofPattern("EEE dd MMM uuuu HH:mm:ss Z", Locale.US)

private fun parseVideoSitePubDateEpochMs(raw: String?): Long? =
    parseEpochMs(raw) { ZonedDateTime.parse(it, KVS_PUB_DATE).toInstant() } ?: parseRfc1123EpochMs(raw)
