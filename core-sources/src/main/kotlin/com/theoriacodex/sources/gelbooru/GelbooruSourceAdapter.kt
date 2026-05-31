package com.theoriacodex.sources.gelbooru

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.model.CreatorProfile
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
import com.theoriacodex.sources.media.inferMimeFromUrl
import com.theoriacodex.sources.media.mimeFromFileExt
import java.io.IOException

class GelbooruSourceAdapter(
    private val httpClient: SourceHttpClient,
    private val credentialsProvider: SourceCredentialsProvider,
    private val gson: Gson = Gson(),
) : SourceAdapter, TagCountLookupSourceAdapter, CreatorPostsSourceAdapter {
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

    override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> {
        val normalizedTags = tags
            .asSequence()
            .map(::normalizeGelbooruTagToken)
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (normalizedTags.isEmpty()) return emptyMap()

        val countsByName = linkedMapOf<String, Int>()
        normalizedTags.chunked(GELBOORU_TAG_COUNT_BATCH_SIZE).forEach { chunk ->
            val response = request(
                query = baseQuery(
                    "s" to "tag",
                    "q" to "index",
                    "names" to chunk.joinToString(" "),
                    "limit" to chunk.size.toString(),
                )
            )
            parseTagItems(response).forEach tagItemLoop@{ raw ->
                val name = raw.get("name")?.asString?.trim().orEmpty()
                if (name.isBlank()) return@tagItemLoop
                val count = raw.get("count")?.asInt ?: raw.get("post_count")?.asInt ?: return@tagItemLoop
                countsByName.putIfAbsent(name, count)
            }
        }
        return countsByName
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

    override suspend fun searchCreatorPosts(
        creator: CreatorProfile,
        pageToken: String?,
    ): Page<Post> {
        if (creator.source != SourceKey.GELBOORU) return Page(items = emptyList(), nextPageToken = null)
        val uploadsQuery = creator.uploadsQuery?.trim().takeIf { !it.isNullOrBlank() }
            ?: return Page(items = emptyList(), nextPageToken = null)
        val pageIndex = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = 40
        val response = request(
            query = baseQuery(
                "s" to "post",
                "q" to "index",
                "pid" to pageIndex.toString(),
                "limit" to limit.toString(),
                "tags" to uploadsQuery,
            ),
        )
        val posts = parsePostItems(response).mapNotNull(::parsePost)
        val next = if (posts.size >= limit) (pageIndex + 1).toString() else null
        return Page(items = posts, nextPageToken = next)
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
        val sampleUrl = raw.get("sample_url")?.asString?.trim()?.takeIf(String::isNotBlank)
        val previewUrl = raw.get("preview_url")?.asString ?: sampleUrl ?: fullUrl
        val fullMime = inferMimeFromUrl(fullUrl) ?: mimeFromFileExt(raw.get("file_ext")?.asString)
        val previewMime = inferMimeFromUrl(previewUrl) ?: fullMime
        val createdAt = raw.get("created_at")?.asString?.toLongOrNull()?.times(1000L)
            ?: raw.get("change")?.asString?.toLongOrNull()?.times(1000L)
        val owner = raw.get("owner")?.asString?.trim().orEmpty()
        val creatorId = raw.get("creator_id")?.asString?.trim().orEmpty()
        val creatorDisplayName = owner.ifBlank { creatorId }.ifBlank { null }
        val uploadsQuery = owner.takeIf { it.isNotBlank() }?.let { "user:$it" }

        return Post(
            id = PostId(SourceKey.GELBOORU, id),
            preview = ImageRef(url = previewUrl, localPath = null, mime = previewMime),
            full = fullUrl?.let {
                ImageRef(
                    url = it,
                    localPath = null,
                    mime = fullMime,
                    progressiveUrls = listOfNotNull(sampleUrl).filter { candidate -> candidate != it },
                )
            },
            pageUrl = "https://gelbooru.com/index.php?page=post&s=view&id=$id",
            width = raw.get("width")?.asInt,
            height = raw.get("height")?.asInt,
            canonicalTags = tags,
            rawTags = tags,
            authorName = creatorDisplayName,
            createdAtEpochMs = createdAt,
            durationMs = parseGelbooruDurationMs(raw),
            creatorProfile = creatorDisplayName?.let { displayName ->
                CreatorProfile(
                    source = SourceKey.GELBOORU,
                    displayName = displayName,
                    profileId = creatorId.ifBlank { null },
                    profileUrl = creatorId.ifBlank { null }
                        ?.let { "https://gelbooru.com/index.php?page=account&s=profile&id=$it" },
                    uploadsQuery = uploadsQuery,
                )
            },
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

private fun parseGelbooruDurationMs(raw: JsonObject): Long? {
    return sequenceOf(
        raw.durationFieldMs("duration_ms", multiplier = 1L),
        raw.durationFieldMs("durationMs", multiplier = 1L),
        raw.durationFieldMs("duration_seconds", multiplier = 1_000L),
        raw.durationFieldMs("durationSeconds", multiplier = 1_000L),
        raw.ambiguousDurationFieldMs("duration"),
        raw.ambiguousDurationFieldMs("video_duration"),
        raw.durationFieldMs("length_seconds", multiplier = 1_000L),
        raw.ambiguousDurationFieldMs("length"),
    ).filterNotNull().firstOrNull { it > 0L }
}

private fun JsonObject.ambiguousDurationFieldMs(name: String): Long? {
    val element = get(name)?.takeUnless { it.isJsonNull } ?: return null
    if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
        val numeric = runCatching { element.asDouble }.getOrNull() ?: return null
        val multiplier = if (numeric >= 1_000.0) 1L else 1_000L
        return (numeric * multiplier).toLong().takeIf { it > 0L }
    }
    return durationFieldMs(name = name, multiplier = 1_000L)
}

private fun JsonObject.durationFieldMs(name: String, multiplier: Long): Long? {
    val element = get(name)?.takeUnless { it.isJsonNull } ?: return null
    val parsed = when {
        element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
            runCatching { (element.asDouble * multiplier).toLong() }.getOrNull()
        }
        element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
            parseFlexibleGelbooruDurationMs(element.asString, multiplier)
        }
        else -> null
    }
    return parsed?.takeIf { it > 0L }
}

private fun parseFlexibleGelbooruDurationMs(raw: String, numericMultiplier: Long): Long? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    trimmed.toDoubleOrNull()?.let { return (it * numericMultiplier).toLong() }
    val parts = trimmed.split(':').mapNotNull { part -> part.trim().toLongOrNull() }
    if (parts.isEmpty() || parts.size != trimmed.count { it == ':' } + 1) return null
    val seconds = when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] * 60L + parts[1]
        else -> parts.takeLast(3).let { (hours, minutes, seconds) ->
            hours * 3600L + minutes * 60L + seconds
        }
    }
    return seconds * 1_000L
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

private fun normalizeGelbooruTagToken(value: String): String {
    return value.trim().replace(WHITESPACE_REGEX, "_")
}

private const val GELBOORU_DAPI_URL = "https://gelbooru.com/index.php"
private const val GELBOORU_TAG_COUNT_BATCH_SIZE = 50
private val WHITESPACE_REGEX = Regex("\\s+")
