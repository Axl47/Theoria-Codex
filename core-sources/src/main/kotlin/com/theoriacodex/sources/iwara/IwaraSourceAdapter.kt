package com.theoriacodex.sources.iwara

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
import com.theoriacodex.domain.model.CreatorProfile
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
import java.time.Instant

class IwaraSourceAdapter(
    private val httpClient: SourceHttpClient,
    private val gson: Gson = Gson(),
) : SourceAdapter, CreatorPostsSourceAdapter {
    override val sourceKey: SourceKey = SourceKey.IWARA

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = false,
        supportsExcludeTagsServerSide = false,
        supportsDateRangeServerSide = false,
        supportsMinScoreServerSide = false,
        requiresCredentials = false,
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        val pageIndex = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val response = requestJsonObject(
            url = "$IWARA_API_BASE/search",
            query = linkedMapOf(
                "type" to "videos",
                "page" to pageIndex.toString(),
                "query" to compileQueryText(query),
                "sort" to mapSort(query.sort),
            ),
        )
        return parsePagedPosts(response)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
        if (limit <= 0) return emptyList()
        val response = requestJsonObject(
            url = "$IWARA_API_BASE/videos",
            query = linkedMapOf(
                "rating" to "all",
                "sort" to "date",
                "page" to "0",
            ),
        )
        val counts = linkedMapOf<String, Int>()
        parseResultItems(response).forEach { item ->
            parseTags(item).forEach { tag ->
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { (tag, count) ->
                TagSuggestion(
                    text = tag,
                    type = "tag",
                    count = count,
                )
            }
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        val normalized = prefix.trim()
        if (normalized.isBlank() || limit <= 0) return emptyList()
        val response = requestJsonObject(
            url = "$IWARA_API_BASE/tags",
            query = linkedMapOf(
                "query" to normalized,
                "page" to "0",
            ),
        )
        return response.getAsJsonArray("results")
            ?.mapNotNull { element ->
                val tag = element.asJsonObject
                val text = tag.string("id").orEmpty().trim()
                if (text.isBlank()) return@mapNotNull null
                TagSuggestion(
                    text = text,
                    type = tag.string("type"),
                    count = null,
                )
            }
            ?.take(limit)
            .orEmpty()
    }

    override suspend fun quickQuery(kind: QuickQueryKind): Query {
        val sort = when (kind) {
            QuickQueryKind.POPULAR_TODAY -> SortMode.POPULAR
            QuickQueryKind.TOP_7D -> SortMode.TOP
            QuickQueryKind.TOP_30D -> SortMode.TOP
            QuickQueryKind.NEWEST -> SortMode.NEWEST
            QuickQueryKind.RANDOM -> SortMode.NEWEST
        }
        return Query(
            mode = QueryMode.Source(SourceKey.IWARA),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = sort,
            dateRange = null,
            minScore = null,
        )
    }

    override suspend fun resolvePost(id: PostId): Post? {
        if (id.source != SourceKey.IWARA) return null
        val response = request(
            url = "$IWARA_API_BASE/video/${id.sourcePostId}",
            query = emptyMap(),
        )
        if (response.statusCode == 404) return null
        val root = parseJsonObject(response.body)
        return parseVideoPost(root)
    }

    override suspend fun searchCreatorPosts(
        creator: CreatorProfile,
        pageToken: String?,
    ): Page<Post> {
        if (creator.source != SourceKey.IWARA) return Page(emptyList(), null)
        val userId = creator.uploadsQuery?.trim().takeUnless { it.isNullOrBlank() }
            ?: creator.profileId?.trim().takeUnless { it.isNullOrBlank() }
            ?: return Page(emptyList(), null)
        val pageIndex = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val response = requestJsonObject(
            url = "$IWARA_API_BASE/videos",
            query = linkedMapOf(
                "rating" to "all",
                "sort" to "date",
                "page" to pageIndex.toString(),
                "user" to userId,
            ),
        )
        return parsePagedPosts(response)
    }

    private fun compileQueryText(query: Query): String {
        return query.includeTags
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" ")
    }

    private fun mapSort(sort: SortMode): String {
        return when (sort) {
            SortMode.NEWEST -> "date"
            SortMode.POPULAR -> "views"
            SortMode.TOP -> "likes"
            SortMode.RANDOM -> "date"
        }
    }

    private suspend fun requestJsonObject(
        url: String,
        query: Map<String, String>,
    ): JsonObject {
        val response = request(url = url, query = query)
        return parseJsonObject(response.body)
    }

    private suspend fun request(
        url: String,
        query: Map<String, String>,
    ) = try {
        httpClient.get(
            url = url,
            query = query,
            headers = IWARA_REQUEST_HEADERS,
        )
    } catch (error: IOException) {
        throw SourceAdapterException(
            reason = SourceFailureReason.NETWORK,
            message = "Iwara request failed",
            cause = error,
        )
    }.also { response ->
        if (response.statusCode !in 200..299 && response.statusCode != 404) {
            throw SourceAdapterException(
                reason = classifyFailure(response.statusCode),
                message = "Iwara request failed (${response.statusCode})",
            )
        }
    }

    private fun parseJsonObject(body: String): JsonObject {
        val root = runCatching { gson.fromJson(body, JsonElement::class.java) }.getOrNull()
            ?: throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "Iwara returned malformed JSON",
            )
        return root.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "Iwara returned non-object JSON",
            )
    }

    private fun parsePagedPosts(root: JsonObject): Page<Post> {
        val page = root.int("page") ?: 0
        val limit = root.int("limit") ?: 0
        val count = root.int("count") ?: 0
        val items = parseResultItems(root).mapNotNull(::parseVideoPost)
        val hasMore = limit > 0 && ((page + 1) * limit) < count
        return Page(
            items = items,
            nextPageToken = if (hasMore) (page + 1).toString() else null,
        )
    }

    private fun parseResultItems(root: JsonObject): List<JsonObject> {
        return root.getAsJsonArray("results")
            ?.mapNotNull { element -> element.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .orEmpty()
    }

    private fun parseVideoPost(raw: JsonObject): Post? {
        val sourcePostId = raw.string("id")?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val slug = raw.string("slug")?.trim().orEmpty()
        val file = raw.objectOrNull("file")
        val customThumbnail = raw.objectOrNull("customThumbnail")
        val fileUrl = raw.string("fileUrl")?.trim().takeUnless { it.isNullOrBlank() }
        val fullMime = file?.string("mime") ?: mimeFromFileExt(file?.string("name")?.substringAfterLast('.'))
        val full = fileUrl?.let { url ->
            ImageRef(
                url = url,
                localPath = null,
                mime = fullMime ?: inferMimeFromUrl(url),
            )
        }
        val preview = ImageRef(
            url = customThumbnail?.let(::buildAssetUrl) ?: file?.string("id")?.let(::buildVideoThumbnailUrl),
            localPath = null,
            mime = customThumbnail?.string("mime") ?: "image/jpeg",
        )
        val tags = parseTags(raw)
        val creatorProfile = parseCreator(raw.objectOrNull("user"))
        return Post(
            id = PostId(source = SourceKey.IWARA, sourcePostId = sourcePostId),
            preview = preview,
            full = full,
            media = full?.let(::listOf).orEmpty(),
            pageUrl = buildVideoPageUrl(sourcePostId = sourcePostId, slug = slug),
            width = file?.int("width") ?: customThumbnail?.int("width"),
            height = file?.int("height") ?: customThumbnail?.int("height"),
            canonicalTags = tags,
            rawTags = tags,
            authorName = creatorProfile?.displayName,
            createdAtEpochMs = raw.string("createdAt")?.let(::parseEpochMs),
            title = raw.string("title")?.trim().takeUnless { it.isNullOrBlank() },
            creatorProfile = creatorProfile,
        )
    }

    private fun parseTags(raw: JsonObject): List<String> {
        return raw.getAsJsonArray("tags")
            ?.mapNotNull { element ->
                element.takeIf(JsonElement::isJsonObject)?.asJsonObject?.string("id")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }
            .orEmpty()
    }

    private fun parseCreator(user: JsonObject?): CreatorProfile? {
        user ?: return null
        val displayName = user.string("name")?.trim().takeUnless { it.isNullOrBlank() }
            ?: user.string("username")?.trim().takeUnless { it.isNullOrBlank() }
            ?: return null
        val userId = user.string("id")?.trim().takeUnless { it.isNullOrBlank() }
        val username = user.string("username")?.trim().takeUnless { it.isNullOrBlank() }
        return CreatorProfile(
            source = SourceKey.IWARA,
            displayName = displayName,
            profileId = userId,
            profileUrl = username?.let { "https://www.iwara.tv/profile/$it/videos" },
            uploadsQuery = userId,
        )
    }

    private fun buildAssetUrl(asset: JsonObject): String? {
        val assetId = asset.string("id")?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val fileExt = asset.string("name")
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase()
            ?.ifBlank { "jpg" }
            ?: "jpg"
        return "https://i.iwara.tv/image/original/$assetId/$assetId.$fileExt"
    }

    private fun buildVideoThumbnailUrl(fileId: String): String {
        return "https://i.iwara.tv/image/thumbnail/$fileId/$fileId.jpg"
    }

    private fun buildVideoPageUrl(sourcePostId: String, slug: String): String {
        return if (slug.isBlank()) {
            "https://www.iwara.tv/video/$sourcePostId"
        } else {
            "https://www.iwara.tv/video/$sourcePostId/$slug"
        }
    }

    private fun parseEpochMs(value: String): Long? {
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private fun classifyFailure(statusCode: Int): SourceFailureReason {
        return when (statusCode) {
            401, 403 -> SourceFailureReason.AUTH_REQUIRED
            429 -> SourceFailureReason.RATE_LIMITED
            in 500..599 -> SourceFailureReason.NETWORK
            else -> SourceFailureReason.UNKNOWN
        }
    }
}

private fun JsonObject.string(name: String): String? {
    return get(name)?.takeUnless { it.isJsonNull }?.asString
}

private fun JsonObject.int(name: String): Int? {
    return get(name)?.takeUnless { it.isJsonNull }?.asInt
}

private fun JsonObject.objectOrNull(name: String): JsonObject? {
    return get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
}

private const val IWARA_API_BASE = "https://api.iwara.tv"
private val IWARA_REQUEST_HEADERS = mapOf(
    "Referer" to "https://www.iwara.tv/",
    "User-Agent" to "Mozilla/5.0",
)
