package com.theoriacodex.sources.gelbooru

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import java.io.IOException

class GelbooruSourceAdapter(
    private val httpClient: SourceHttpClient,
    private val credentialsProvider: SourceCredentialsProvider,
    private val gson: Gson = Gson(),
) : SourceAdapter {
    override val sourceKey: SourceKey = SourceKey.GELBOORU

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = false,
        supportsExcludeTagsServerSide = true,
        supportsDateRangeServerSide = false,
        supportsMinScoreServerSide = false,
        requiresCredentials = false,
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        val pageIndex = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = 40
        val response = request(
            query = baseQuery(
                "s" to "post",
                "q" to "index",
                "pid" to pageIndex.toString(),
                "limit" to limit.toString(),
                "tags" to compileTags(query),
            ),
        )
        val posts = parsePostItems(response).mapNotNull { parsePost(it) }
        val next = if (posts.size >= limit) (pageIndex + 1).toString() else null
        return Page(items = posts, nextPageToken = next)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
        val response = request(
            query = baseQuery(
                "s" to "tag",
                "q" to "index",
                "orderby" to "count",
                "order" to "desc",
                "limit" to limit.coerceIn(1, 50).toString(),
            ),
        )
        return parseTagItems(response).mapNotNull { parseTag(it, "trending") }
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        if (prefix.isBlank()) return emptyList()
        val response = request(
            query = baseQuery(
                "s" to "tag",
                "q" to "index",
                "name_pattern" to "${prefix.trim()}%",
                "orderby" to "count",
                "order" to "desc",
                "limit" to limit.coerceIn(1, 30).toString(),
            ),
        )
        return parseTagItems(response).mapNotNull { parseTag(it, "tag") }
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
            QuickQueryKind.TOP_7D -> DateRange(now - 7L * dayMs, now)
            QuickQueryKind.TOP_30D -> DateRange(now - 30L * dayMs, now)
            QuickQueryKind.NEWEST, QuickQueryKind.RANDOM -> null
        }
        return Query(
            mode = QueryMode.Source(SourceKey.GELBOORU),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = sort,
            dateRange = dateRange,
            minScore = null,
        )
    }

    override suspend fun resolvePost(id: PostId): Post? {
        if (id.source != SourceKey.GELBOORU) return null
        val response = request(
            query = baseQuery(
                "s" to "post",
                "q" to "index",
                "tags" to "id:${id.sourcePostId}",
                "limit" to "1",
            ),
        )
        return parsePostItems(response).firstOrNull()?.let(::parsePost)
    }

    private suspend fun request(query: Map<String, String>): String {
        val response = try {
            httpClient.get(
                url = GELBOORU_DAPI_URL,
                query = query,
            )
        } catch (error: IOException) {
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "Gelbooru request failed",
                cause = error,
            )
        }

        if (response.statusCode !in 200..299) {
            throw SourceAdapterException(
                reason = classifyFailure(response.statusCode, response.body),
                message = "Gelbooru request failed (${response.statusCode})",
            )
        }

        if (looksLikeAuthBlocked(response.body)) {
            throw SourceAdapterException(
                reason = SourceFailureReason.AUTH_REQUIRED,
                message = "Gelbooru rejected request without credentials",
            )
        }
        return response.body
    }

    private suspend fun baseQuery(vararg entries: Pair<String, String>): Map<String, String> {
        val query = linkedMapOf(
            "page" to "dapi",
            "json" to "1",
        )
        entries.forEach { (k, v) -> query[k] = v }
        credentialsProvider.getGelbooruCredentials()?.let { credentials ->
            query["user_id"] = credentials.userId
            query["api_key"] = credentials.apiKey
        }
        return query
    }

    private fun parsePostItems(body: String): List<JsonObject> {
        val element = runCatching { gson.fromJson(body, JsonElement::class.java) }.getOrNull()
            ?: throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "Gelbooru returned malformed JSON",
            )

        return when {
            element.isJsonArray -> element.asJsonArray.toList().mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                when {
                    obj.get("post")?.isJsonArray == true -> obj.getAsJsonArray("post").toList().mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                    obj.get("post")?.isJsonObject == true -> listOf(obj.getAsJsonObject("post"))
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun parseTagItems(body: String): List<JsonObject> {
        val element = runCatching { gson.fromJson(body, JsonElement::class.java) }.getOrNull()
            ?: throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "Gelbooru tags response malformed",
            )

        return when {
            element.isJsonArray -> element.asJsonArray.toList().mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                when {
                    obj.get("tag")?.isJsonArray == true -> obj.getAsJsonArray("tag").toList().mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                    obj.get("tag")?.isJsonObject == true -> listOf(obj.getAsJsonObject("tag"))
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun parsePost(raw: JsonObject): Post? {
        val id = raw.get("id")?.asString?.ifBlank { null } ?: return null
        val tags = raw.get("tags")?.asString
            ?.split(" ")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val fullUrl = raw.get("file_url")?.asString
        val previewUrl = raw.get("preview_url")?.asString ?: raw.get("sample_url")?.asString ?: fullUrl
        val createdAt = raw.get("created_at")?.asString?.toLongOrNull()?.times(1000L)
            ?: raw.get("change")?.asString?.toLongOrNull()?.times(1000L)

        return Post(
            id = PostId(SourceKey.GELBOORU, id),
            preview = ImageRef(url = previewUrl, localPath = null, mime = "image/jpeg"),
            full = fullUrl?.let { ImageRef(url = it, localPath = null, mime = "image/jpeg") },
            pageUrl = "https://gelbooru.com/index.php?page=post&s=view&id=$id",
            width = raw.get("width")?.asInt,
            height = raw.get("height")?.asInt,
            canonicalTags = tags,
            rawTags = tags,
            authorName = raw.get("owner")?.asString ?: raw.get("creator_id")?.asString,
            createdAtEpochMs = createdAt,
        )
    }

    private fun parseTag(raw: JsonObject, type: String): TagSuggestion? {
        val name = raw.get("name")?.asString?.trim().orEmpty()
        if (name.isBlank()) return null
        val count = raw.get("count")?.asInt ?: raw.get("post_count")?.asInt
        return TagSuggestion(
            text = name,
            type = type,
            count = count,
        )
    }
}

private fun compileTags(query: Query): String {
    val include = query.includeTags.map { it.trim() }.filter { it.isNotBlank() }
    val exclude = query.excludeTags.map { it.trim() }.filter { it.isNotBlank() }.map { "-$it" }
    val order = when (query.sort) {
        SortMode.NEWEST -> "sort:id:desc"
        SortMode.POPULAR, SortMode.TOP -> "sort:score:desc"
        SortMode.RANDOM -> "sort:id:desc"
    }
    return (include + exclude + order).take(40).joinToString(" ")
}

private fun classifyFailure(statusCode: Int, body: String): SourceFailureReason {
    return when {
        statusCode == 401 || statusCode == 403 -> SourceFailureReason.AUTH_REQUIRED
        statusCode == 429 -> SourceFailureReason.RATE_LIMITED
        statusCode in 500..599 -> SourceFailureReason.NETWORK
        looksLikeAuthBlocked(body) -> SourceFailureReason.AUTH_REQUIRED
        else -> SourceFailureReason.UNKNOWN
    }
}

private fun looksLikeAuthBlocked(body: String): Boolean {
    val lowered = body.lowercase()
    return "access denied" in lowered || "api key" in lowered && "required" in lowered
}

private const val GELBOORU_DAPI_URL = "https://gelbooru.com/index.php"
