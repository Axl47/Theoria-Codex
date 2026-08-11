package com.theoriacodex.sources.gelbooru

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.common.ambiguousDurationFieldMs
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.common.durationFieldMs
import com.theoriacodex.sources.common.firstDurationMs
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.isSuccessful
import com.theoriacodex.sources.common.longValue
import com.theoriacodex.sources.common.mimeFromUrlOrExt
import com.theoriacodex.sources.common.objectList
import com.theoriacodex.sources.common.parseJsonElement
import com.theoriacodex.sources.common.sourceNetworkFailure
import com.theoriacodex.sources.common.sourceQuickQuery
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
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
        supportsGroupedIncludeTagsServerSide = true,
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
        val rawPosts = parsePostItems(response)
        val posts = rawPosts.mapNotNull { parsePost(it) }
        val next = if (rawPosts.size >= limit) (pageIndex + 1).toString() else null
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
                val name = raw.stringValue("name")?.trim().orEmpty()
                if (name.isBlank()) return@tagItemLoop
                val count = raw.intValue("count") ?: raw.intValue("post_count") ?: return@tagItemLoop
                countsByName.putIfAbsent(name, count)
            }
        }
        return countsByName
    }

    override suspend fun quickQuery(kind: QuickQueryKind): Query {
        return sourceQuickQuery(source = SourceKey.GELBOORU, kind = kind)
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
        val rawPosts = parsePostItems(response)
        val posts = rawPosts.mapNotNull(::parsePost)
        val next = if (rawPosts.size >= limit) (pageIndex + 1).toString() else null
        return Page(items = posts, nextPageToken = next)
    }

    private suspend fun request(query: Map<String, String>): String {
        val response = try {
            httpClient.get(
                url = GELBOORU_DAPI_URL,
                query = query,
            )
        } catch (error: IOException) {
            sourceNetworkFailure("Gelbooru", error)
        }

        if (!response.isSuccessful()) {
            throw SourceAdapterException(
                reason = classifyHttpFailure(
                    statusCode = response.statusCode,
                    body = response.body,
                    authBlockedBody = ::looksLikeAuthBlocked,
                ),
                message = "Gelbooru request failed (${response.statusCode})",
            )
        }

        if (looksLikeAuthBlocked(response.body)) {
            throw SourceAdapterException(
                reason = classifyHttpFailure(
                    statusCode = 200,
                    body = response.body,
                    authBlockedBody = ::looksLikeAuthBlocked,
                ),
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
        val element = parseJsonElement(body = body, gson = gson, errorLabel = "Gelbooru")

        return element.objectList("post")
    }

    private fun parseTagItems(body: String): List<JsonObject> {
        val element = parseJsonElement(body = body, gson = gson, errorLabel = "Gelbooru tags")

        return element.objectList("tag")
    }

    private fun parsePost(raw: JsonObject): Post? {
        val id = raw.stringValue("id")?.ifBlank { null } ?: return null
        val tags = raw.stringValue("tags")
            ?.split(" ")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val fullUrl = normalizeGelbooruMediaUrl(raw.stringValue("file_url"))
        val sampleUrl = normalizeGelbooruMediaUrl(raw.stringValue("sample_url"))
        val previewUrl = normalizeGelbooruMediaUrl(raw.stringValue("preview_url")) ?: sampleUrl ?: fullUrl
        val fullMime = mimeFromUrlOrExt(fullUrl, raw.stringValue("file_ext"))
        val previewMime = mimeFromUrlOrExt(previewUrl, null) ?: fullMime
        val createdAt = raw.longValue("created_at")?.times(1000L)
            ?: raw.longValue("change")?.times(1000L)
        val owner = raw.stringValue("owner")?.trim().orEmpty()
        val creatorId = raw.stringValue("creator_id")?.trim().orEmpty()
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
            width = raw.intValue("width"),
            height = raw.intValue("height"),
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
        val name = raw.stringValue("name")?.trim().orEmpty()
        if (name.isBlank()) return null
        val count = raw.intValue("count") ?: raw.intValue("post_count")
        return TagSuggestion(
            text = name,
            type = type,
            count = count,
        )
    }
}

fun normalizeGelbooruMediaUrl(rawUrl: String?): String? {
    val url = rawUrl?.trim()?.takeIf(String::isNotBlank) ?: return null
    return url.replaceFirst(GELBOORU_NUMBERED_VIDEO_CDN_PREFIX, "https://gelbooru.com/")
}

private fun parseGelbooruDurationMs(raw: JsonObject): Long? {
    return firstDurationMs(
        raw.durationFieldMs("duration_ms", multiplier = 1L),
        raw.durationFieldMs("durationMs", multiplier = 1L),
        raw.durationFieldMs("duration_seconds", multiplier = 1_000L),
        raw.durationFieldMs("durationSeconds", multiplier = 1_000L),
        raw.ambiguousDurationFieldMs("duration"),
        raw.ambiguousDurationFieldMs("video_duration"),
        raw.durationFieldMs("length_seconds", multiplier = 1_000L),
        raw.ambiguousDurationFieldMs("length"),
    )
}

private fun compileTags(query: Query): String {
    val include = query.effectiveIncludeTermGroups.mapNotNull { group ->
        val alternatives = group.terms.map { term -> term.value.trim() }.filter(String::isNotBlank)
        when (alternatives.size) {
            0 -> null
            1 -> alternatives.single()
            else -> alternatives.joinToString(separator = " ~ ", prefix = "{", postfix = "}")
        }
    }
    val exclude = query.excludeTags.map { it.trim() }.filter { it.isNotBlank() }.map { "-$it" }
    val order = when (query.sort) {
        SortMode.NEWEST -> "sort:id:desc"
        SortMode.POPULAR, SortMode.TOP -> "sort:score:desc"
        SortMode.RANDOM -> "sort:id:desc"
    }
    return (include + exclude + order).take(40).joinToString(" ")
}

private fun looksLikeAuthBlocked(body: String): Boolean {
    val lowered = body.lowercase()
    return "access denied" in lowered || "api key" in lowered && "required" in lowered
}

private fun normalizeGelbooruTagToken(value: String): String {
    return value.trim().replace(WHITESPACE_REGEX, "_")
}

private val GELBOORU_NUMBERED_VIDEO_CDN_PREFIX =
    Regex("^https://video-cdn\\d+\\.gelbooru\\.com/", RegexOption.IGNORE_CASE)

private const val GELBOORU_DAPI_URL = "https://gelbooru.com/index.php"
private const val GELBOORU_TAG_COUNT_BATCH_SIZE = 50
private val WHITESPACE_REGEX = Regex("\\s+")
