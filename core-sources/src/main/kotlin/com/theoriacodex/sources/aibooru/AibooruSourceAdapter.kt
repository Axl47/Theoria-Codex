package com.theoriacodex.sources.aibooru

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.common.mimeFromUrlOrExt
import com.theoriacodex.sources.common.parseJsonArray
import com.theoriacodex.sources.common.parseJsonObject
import com.theoriacodex.sources.common.sourceNetworkFailure
import com.theoriacodex.sources.common.sourceQuickQuery
import java.io.IOException

class AibooruSourceAdapter(
    private val httpClient: SourceHttpClient,
    private val gson: Gson = Gson(),
) : SourceAdapter {
    override val sourceKey: SourceKey = SourceKey.AIBOORU

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = false,
        supportsExcludeTagsServerSide = true,
        supportsDateRangeServerSide = false,
        supportsMinScoreServerSide = true,
        requiresCredentials = false,
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        val page = pageToken?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val limit = 40
        val response = request(
            path = "/posts.json",
            query = mapOf(
                "limit" to limit.toString(),
                "page" to page.toString(),
                "tags" to compileTags(query),
            ),
        )
        val items = parsePostsArray(response).mapNotNull { element ->
            parsePost(element.asJsonObject)
        }
        val nextPageToken = if (items.size >= limit) (page + 1).toString() else null
        return Page(items = items, nextPageToken = nextPageToken)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
        val response = request(
            path = "/tags.json",
            query = mapOf(
                "limit" to limit.coerceIn(1, 50).toString(),
                "search[order]" to "count",
            ),
        )
        val tags = parseArray(response)
        return tags.mapNotNull { parseTagSuggestion(it, "trending") }
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        if (prefix.isBlank()) return emptyList()
        val response = request(
            path = "/tags.json",
            query = mapOf(
                "limit" to limit.coerceIn(1, 30).toString(),
                "search[name_matches]" to "${prefix.trim()}*",
                "search[order]" to "count",
            ),
        )
        val tags = parseArray(response)
        return tags.mapNotNull { parseTagSuggestion(it, "tag") }
    }

    override suspend fun quickQuery(kind: QuickQueryKind): Query {
        return sourceQuickQuery(source = SourceKey.AIBOORU, kind = kind)
    }

    override suspend fun resolvePost(id: PostId): Post? {
        if (id.source != SourceKey.AIBOORU) return null
        val response = request(path = "/posts/${id.sourcePostId}.json")
        val objectBody = parseObject(response)
        return parsePost(objectBody)
    }

    private suspend fun request(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): String {
        val response = try {
            httpClient.get(
                url = "$AIBOORU_BASE_URL$path",
                query = query,
            )
        } catch (error: IOException) {
            sourceNetworkFailure("AIBooru", error)
        }

        if (response.statusCode !in 200..299) {
            throw SourceAdapterException(
                reason = classifyHttpFailure(response.statusCode),
                message = "AIBooru request failed (${response.statusCode})",
            )
        }
        return response.body
    }

    private fun parsePostsArray(body: String): JsonArray {
        return parseArray(body)
    }

    private fun parseArray(body: String): JsonArray {
        return parseJsonArray(body = body, gson = gson, errorLabel = "AIBooru")
    }

    private fun parseObject(body: String): JsonObject {
        return parseJsonObject(body = body, gson = gson, errorLabel = "AIBooru")
    }

    private fun parsePost(raw: JsonObject): Post? {
        val id = raw.get("id")?.asLong?.toString() ?: return null
        val tags = raw.get("tag_string")?.asString
            ?.split(" ")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val preview = raw.get("preview_file_url")?.asString
        val fullUrl = raw.get("file_url")?.asString ?: raw.get("large_file_url")?.asString
        val fullMime = mimeFromUrlOrExt(fullUrl, raw.get("file_ext")?.asString)
        val previewMime = mimeFromUrlOrExt(preview, null) ?: fullMime
        val created = raw.get("created_at")?.asString?.toLongOrNull()
            ?: raw.get("created_at")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

        return Post(
            id = PostId(SourceKey.AIBOORU, id),
            preview = ImageRef(url = preview, localPath = null, mime = previewMime),
            full = fullUrl?.let { ImageRef(url = it, localPath = null, mime = fullMime) },
            pageUrl = "$AIBOORU_BASE_URL/posts/$id",
            width = raw.get("image_width")?.asInt,
            height = raw.get("image_height")?.asInt,
            canonicalTags = tags,
            rawTags = tags,
            authorName = raw.get("uploader_name")?.asString,
            createdAtEpochMs = created?.times(1000L),
        )
    }

    private fun parseTagSuggestion(raw: JsonElement, type: String): TagSuggestion? {
        val obj = raw.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val name = obj.get("name")?.asString?.trim().orEmpty()
        if (name.isBlank()) return null
        val count = obj.get("post_count")?.asInt ?: obj.get("count")?.asInt
        return TagSuggestion(
            text = name,
            type = type,
            count = count,
        )
    }
}

private fun compileTags(query: Query): String {
    val includeTags = query.includeTags.map { it.trim() }.filter { it.isNotBlank() }
    val excludeTags = query.excludeTags
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { "-$it" }

    val metatags = mutableListOf<String>()
    if (query.minScore != null) {
        metatags += "score:>=${query.minScore}"
    }
    if (query.sort == SortMode.TOP || query.sort == SortMode.POPULAR) {
        metatags += "order:score"
    } else {
        metatags += "order:id_desc"
    }
    val allTags = (includeTags + excludeTags + metatags).take(40)
    return allTags.joinToString(" ")
}

private const val AIBOORU_BASE_URL = "https://aibooru.online"
