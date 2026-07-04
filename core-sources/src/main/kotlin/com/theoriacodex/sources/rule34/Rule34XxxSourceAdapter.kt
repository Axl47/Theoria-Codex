package com.theoriacodex.sources.rule34

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.media.inferMimeFromUrl
import com.theoriacodex.sources.media.mimeFromFileExt
import java.io.IOException

class Rule34XxxSourceAdapter(
    private val httpClient: SourceHttpClient,
    private val credentialsProvider: SourceCredentialsProvider,
    private val gson: Gson = Gson(),
) : SourceAdapter, TagCountLookupSourceAdapter {
    override val sourceKey: SourceKey = SourceKey.RULE34XXX

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = false,
        supportsExcludeTagsServerSide = true,
        supportsDateRangeServerSide = false,
        supportsMinScoreServerSide = true,
        requiresCredentials = true,
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        val pageIndex = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = 40
        val response = requestJson(
            query = authenticatedQuery(
                "page" to "dapi",
                "s" to "post",
                "q" to "index",
                "json" to "1",
                "limit" to limit.toString(),
                "pid" to pageIndex.toString(),
                "tags" to compileRule34BooruTags(query),
            ),
        )
        val posts = jsonArrayOfObjects(response, gson, "rule34.xxx posts")
            .mapNotNull(::parsePost)
        val next = if (posts.size >= limit) (pageIndex + 1).toString() else null
        return Page(items = posts, nextPageToken = next)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
        val response = requestJson(
            query = authenticatedQuery(
                "page" to "dapi",
                "s" to "tag",
                "q" to "index",
                "json" to "1",
                "orderby" to "count",
                "order" to "desc",
                "limit" to limit.coerceIn(1, 50).toString(),
            ),
        )
        return jsonArrayOfObjects(response, gson, "rule34.xxx tags")
            .mapNotNull { raw -> parseTagSuggestion(raw, "trending") }
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        val normalized = prefix.trim()
        if (normalized.isBlank()) return emptyList()
        val response = requestAutocomplete(normalized)
        return parseRule34XxxAutocompleteSuggestions(response, gson).take(limit)
    }

    override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> {
        val normalized = tags
            .asSequence()
            .map(::normalizeRule34BooruTag)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (normalized.isEmpty()) return emptyMap()

        val counts = linkedMapOf<String, Int>()
        normalized.chunked(50).forEach { chunk ->
            val response = requestJson(
                query = authenticatedQuery(
                    "page" to "dapi",
                    "s" to "tag",
                    "q" to "index",
                    "json" to "1",
                    "names" to chunk.joinToString(" "),
                    "limit" to chunk.size.toString(),
                ),
            )
            jsonArrayOfObjects(response, gson, "rule34.xxx tags").forEach tagRecords@{ raw ->
                val name = raw.get("name")?.asString?.trim().orEmpty()
                val count = raw.get("count")?.asInt ?: raw.get("post_count")?.asInt ?: return@tagRecords
                if (name.isNotBlank()) {
                    counts.putIfAbsent(name, count)
                }
            }
        }
        return counts
    }

    override suspend fun quickQuery(kind: QuickQueryKind): Query {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L
        val sort = when (kind) {
            QuickQueryKind.POPULAR_TODAY -> SortMode.POPULAR
            QuickQueryKind.TOP_7D -> SortMode.TOP
            QuickQueryKind.TOP_30D -> SortMode.TOP
            QuickQueryKind.NEWEST -> SortMode.NEWEST
            QuickQueryKind.RANDOM -> SortMode.NEWEST
        }
        val dateRange = when (kind) {
            QuickQueryKind.POPULAR_TODAY -> DateRange(now - dayMs, now)
            QuickQueryKind.TOP_7D -> DateRange(now - (7L * dayMs), now)
            QuickQueryKind.TOP_30D -> DateRange(now - (30L * dayMs), now)
            QuickQueryKind.NEWEST, QuickQueryKind.RANDOM -> null
        }
        return Query(
            mode = QueryMode.Source(SourceKey.RULE34XXX),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = sort,
            dateRange = dateRange,
            minScore = null,
        )
    }

    override suspend fun resolvePost(id: PostId): Post? {
        if (id.source != SourceKey.RULE34XXX) return null
        val response = requestJson(
            query = authenticatedQuery(
                "page" to "dapi",
                "s" to "post",
                "q" to "index",
                "json" to "1",
                "tags" to "id:${id.sourcePostId}",
                "limit" to "1",
            ),
        )
        return jsonArrayOfObjects(response, gson, "rule34.xxx posts").firstOrNull()?.let(::parsePost)
    }

    private suspend fun requestJson(query: Map<String, String>): String {
        val response = try {
            httpClient.get(
                url = RULE34XXX_API_URL,
                query = query,
                headers = RULE34_BROWSER_HEADERS,
            )
        } catch (error: IOException) {
            rule34NetworkFailure("rule34.xxx", error)
        }

        if (response.statusCode !in 200..299) {
            throw SourceAdapterException(
                reason = classifyRule34HttpFailure(response.statusCode),
                message = "rule34.xxx request failed (${response.statusCode})",
            )
        }
        if (looksLikeAuthFailure(response.body)) {
            throw SourceAdapterException(
                reason = SourceFailureReason.AUTH_REQUIRED,
                message = "rule34.xxx credentials are missing or invalid",
            )
        }
        return response.body
    }

    private suspend fun requestAutocomplete(prefix: String): String {
        val response = try {
            httpClient.get(
                url = RULE34XXX_AUTOCOMPLETE_URL,
                query = mapOf("q" to prefix),
                headers = RULE34_BROWSER_HEADERS,
            )
        } catch (error: IOException) {
            rule34NetworkFailure("rule34.xxx autocomplete", error)
        }

        if (response.statusCode !in 200..299) {
            throw SourceAdapterException(
                reason = classifyRule34HttpFailure(response.statusCode),
                message = "rule34.xxx autocomplete failed (${response.statusCode})",
            )
        }
        return response.body
    }

    private suspend fun authenticatedQuery(vararg entries: Pair<String, String>): Map<String, String> {
        val credentials = credentialsProvider.getRule34XxxCredentials()
            ?: throw SourceAdapterException(
                reason = SourceFailureReason.AUTH_REQUIRED,
                message = "rule34.xxx credentials required",
            )
        return linkedMapOf<String, String>().apply {
            entries.forEach { (key, value) -> put(key, value) }
            put("user_id", credentials.userId)
            put("api_key", credentials.apiKey)
        }
    }

    private fun parsePost(raw: JsonObject): Post? {
        val id = raw.get("id")?.asString?.trim().orEmpty()
        if (id.isBlank()) return null

        val tags = raw.get("tags")?.asString
            ?.split(' ')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
        val fullUrl = raw.get("file_url")?.asString
        val previewUrl = raw.get("preview_url")?.asString ?: raw.get("sample_url")?.asString ?: fullUrl
        val fullMime = inferMimeFromUrl(fullUrl) ?: mimeFromFileExt(raw.get("file_ext")?.asString)
        val previewMime = inferMimeFromUrl(previewUrl) ?: fullMime
        val createdAt = raw.get("created_at")?.asString?.toLongOrNull()?.times(1000L)
            ?: raw.get("change")?.asString?.toLongOrNull()?.times(1000L)

        return Post(
            id = PostId(source = SourceKey.RULE34XXX, sourcePostId = id),
            preview = ImageRef(url = previewUrl, localPath = null, mime = previewMime),
            full = fullUrl?.let { ImageRef(url = it, localPath = null, mime = fullMime) },
            pageUrl = "$RULE34XXX_SITE_URL/index.php?page=post&s=view&id=$id",
            width = raw.get("width")?.asInt,
            height = raw.get("height")?.asInt,
            canonicalTags = tags,
            rawTags = tags,
            authorName = raw.get("owner")?.asString ?: raw.get("author")?.asString,
            createdAtEpochMs = createdAt,
        )
    }

    private fun parseTagSuggestion(raw: JsonObject, type: String): TagSuggestion? {
        val text = raw.get("name")?.asString?.trim().orEmpty()
        if (text.isBlank()) return null
        return TagSuggestion(
            text = text,
            type = type,
            count = raw.get("count")?.asInt ?: raw.get("post_count")?.asInt,
        )
    }

    private fun looksLikeAuthFailure(body: String): Boolean {
        val normalized = body.trim().lowercase()
        return normalized.contains("missing authentication") ||
            normalized.contains("invalid api key") ||
            normalized.contains("invalid user")
    }
}

private const val RULE34XXX_API_URL = "https://api.rule34.xxx/index.php"
private const val RULE34XXX_AUTOCOMPLETE_URL = "https://api.rule34.xxx/autocomplete.php"
private const val RULE34XXX_SITE_URL = "https://rule34.xxx"
