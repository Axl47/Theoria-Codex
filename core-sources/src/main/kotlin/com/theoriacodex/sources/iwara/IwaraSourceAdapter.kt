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
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.common.durationFieldMs
import com.theoriacodex.sources.common.firstDurationMs
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.isSuccessful
import com.theoriacodex.sources.common.matchesChallenge
import com.theoriacodex.sources.common.optionalJsonArray
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.parseJsonElement
import com.theoriacodex.sources.common.parseJsonObject
import com.theoriacodex.sources.common.sourceNetworkFailure
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.media.inferMimeFromUrl
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
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
        val compiledQuery = compileQueryText(query)
        if (compiledQuery.isBlank()) {
            val response = requestJsonObject(
                url = "$IWARA_API_BASE/videos",
                query = linkedMapOf(
                    "rating" to "all",
                    "sort" to mapSort(query.sort),
                    "page" to pageIndex.toString(),
                ),
            )
            return parsePagedPosts(response)
        }
        val response = requestJsonObject(
            url = "$IWARA_API_BASE/search",
            query = linkedMapOf(
                "type" to "videos",
                "page" to pageIndex.toString(),
                "query" to compiledQuery,
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
        return response.optionalJsonArray("results")
            ?.mapNotNull { element ->
                val tag = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
                val text = tag.stringValue("id").orEmpty().trim()
                if (text.isBlank()) return@mapNotNull null
                TagSuggestion(
                    text = text,
                    type = tag.stringValue("type"),
                    count = null,
                )
            }
            ?.filter { suggestion ->
                suggestion.text.contains(normalized, ignoreCase = true)
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
        val root = parseJsonObject(response.body, gson, "Iwara")
        val parsed = parseVideoPost(root) ?: return null
        val resolvedMedia = root.stringValue("fileUrl")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { fileUrl ->
                resolvePlayableVideoRef(
                    fileUrl = fileUrl,
                    fallbackMime = root.optionalJsonObject("file")?.stringValue("mime"),
                )
            }
        if (resolvedMedia == null) {
            return parsed.copy(
                full = null,
                media = emptyList(),
            )
        }
        return parsed.copy(
            full = resolvedMedia,
            media = listOf(resolvedMedia),
        )
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
        return parseJsonObject(response.body, gson, "Iwara")
    }

    private suspend fun request(
        url: String,
        query: Map<String, String>,
    ): SourceHttpResponse {
        val response = try {
            httpClient.get(
                url = url,
                query = query,
                headers = IWARA_REQUEST_HEADERS,
            )
        } catch (error: IOException) {
            sourceNetworkFailure("Iwara", error)
        }
        val effectiveResponse = if (shouldUseMirrorFallback(response)) {
            requestMirror(url = url, query = query)
        } else {
            response
        }
        if (!effectiveResponse.isSuccessful() && effectiveResponse.statusCode != 404) {
            throw SourceAdapterException(
                reason = classifyHttpFailure(effectiveResponse.statusCode),
                message = "Iwara request failed (${effectiveResponse.statusCode})",
            )
        }
        return effectiveResponse
    }

    private suspend fun requestMirror(
        url: String,
        query: Map<String, String>,
    ): SourceHttpResponse {
        val mirrorUrl = JINA_MIRROR_BASE + buildAbsoluteUrl(url, query).encodeJinaTargetUrl()
        val mirrored = try {
            httpClient.get(
                url = mirrorUrl,
                query = emptyMap(),
                headers = JINA_REQUEST_HEADERS,
            )
        } catch (error: IOException) {
            sourceNetworkFailure("Iwara mirror", error)
        }
        return mirrored.copy(body = extractJsonBody(mirrored.body))
    }

    private fun shouldUseMirrorFallback(response: SourceHttpResponse): Boolean {
        return response.matchesChallenge()
    }

    private fun extractJsonBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return trimmed
        val markdownIndex = trimmed.indexOf("Markdown Content:")
        val candidate = if (markdownIndex >= 0) {
            trimmed.substring(markdownIndex + "Markdown Content:".length).trim()
        } else {
            trimmed
        }
        val jsonIndex = candidate.indexOf('{')
        if (jsonIndex >= 0) {
            return candidate.substring(jsonIndex).trim()
        }
        throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "Iwara mirror returned non-JSON content",
        )
    }

    private fun buildAbsoluteUrl(
        baseUrl: String,
        query: Map<String, String>,
    ): String {
        if (query.isEmpty()) return baseUrl
        val separator = if ("?" in baseUrl) "&" else "?"
        val encoded = query.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
        return baseUrl + separator + encoded
    }

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun String.encodeJinaTargetUrl(): String {
        return replace("?", "%3F")
            .replace("&", "%26")
            .replace("=", "%3D")
    }

    private suspend fun resolvePlayableVideoRef(
        fileUrl: String,
        fallbackMime: String?,
    ): ImageRef? {
        val response = request(url = fileUrl, query = emptyMap())
        val root = parseJsonElement(response.body, gson, "Iwara")
        if (!root.isJsonArray) return null
        val variants = root.asJsonArray.mapNotNull { element ->
            val variant = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val src = variant.optionalJsonObject("src")
            val resolvedUrl = src?.stringValue("view")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(::normalizeVariantUrl)
                ?: src?.stringValue("download")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::normalizeVariantUrl)
                ?: return@mapNotNull null
            ResolvedVariant(
                name = variant.stringValue("name")?.trim(),
                mime = variant.stringValue("type")?.trim(),
                url = resolvedUrl,
            )
        }
        val selected = variants.maxByOrNull(::variantPriority) ?: return null
        return ImageRef(
            url = selected.url,
            localPath = null,
            mime = selected.mime ?: fallbackMime ?: inferMimeFromUrl(selected.url),
        )
    }

    private fun normalizeVariantUrl(rawUrl: String): String {
        return if (rawUrl.startsWith("//")) {
            "https:$rawUrl"
        } else {
            rawUrl
        }
    }

    private fun variantPriority(variant: ResolvedVariant): Int {
        val normalized = variant.name?.lowercase().orEmpty()
        if (normalized == "preview") return -1
        if (normalized == "source" || normalized == "original") return Int.MAX_VALUE
        val digits = normalized.filter(Char::isDigit)
        return digits.toIntOrNull() ?: 0
    }

    private fun parsePagedPosts(root: JsonObject): Page<Post> {
        val page = root.intValue("page") ?: 0
        val limit = root.intValue("limit") ?: 0
        val count = root.intValue("count") ?: 0
        val items = parseResultItems(root).mapNotNull(::parseVideoPost)
        val hasMore = limit > 0 && ((page + 1) * limit) < count
        return Page(
            items = items,
            nextPageToken = if (hasMore) (page + 1).toString() else null,
        )
    }

    private fun parseResultItems(root: JsonObject): List<JsonObject> {
        return root.optionalJsonArray("results")
            ?.mapNotNull { element -> element.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .orEmpty()
    }

    private fun parseVideoPost(raw: JsonObject): Post? {
        val sourcePostId = raw.stringValue("id")?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val slug = raw.stringValue("slug")?.trim().orEmpty()
        val file = raw.optionalJsonObject("file")
        val customThumbnail = raw.optionalJsonObject("customThumbnail")
        val embedUrl = raw.stringValue("embedUrl")?.trim().takeUnless { it.isNullOrBlank() }
        if (file == null && embedUrl != null) return null
        val preview = ImageRef(
            url = customThumbnail?.let(::buildAssetUrl)
                ?: file?.stringValue("id")?.let { fileId ->
                    buildVideoThumbnailUrl(
                        fileId = fileId,
                        thumbnailIndex = raw.intValue("thumbnail"),
                    )
                }
                ?: embedUrl?.let(::buildEmbedThumbnailUrl),
            localPath = null,
            mime = customThumbnail?.stringValue("mime") ?: "image/jpeg",
        )
        val tags = parseTags(raw)
        val creatorProfile = parseCreator(raw.optionalJsonObject("user"))
        return Post(
            id = PostId(source = SourceKey.IWARA, sourcePostId = sourcePostId),
            preview = preview,
            full = null,
            media = emptyList(),
            pageUrl = buildVideoPageUrl(sourcePostId = sourcePostId, slug = slug),
            width = file?.intValue("width") ?: customThumbnail?.intValue("width"),
            height = file?.intValue("height") ?: customThumbnail?.intValue("height"),
            canonicalTags = tags,
            rawTags = tags,
            authorName = creatorProfile?.displayName,
            createdAtEpochMs = raw.stringValue("createdAt")?.let(::parseEpochMs),
            title = raw.stringValue("title")?.trim().takeUnless { it.isNullOrBlank() },
            creatorProfile = creatorProfile,
            durationMs = parseIwaraDurationMs(raw, file),
        )
    }

    private fun parseIwaraDurationMs(raw: JsonObject, file: JsonObject?): Long? {
        return firstDurationMs(
            raw.durationFieldMs("durationMs", multiplier = 1L),
            raw.durationFieldMs("duration", multiplier = 1_000L),
            raw.durationFieldMs("durationSeconds", multiplier = 1_000L),
            raw.durationFieldMs("length", multiplier = 1_000L),
            file?.durationFieldMs("durationMs", multiplier = 1L),
            file?.durationFieldMs("duration", multiplier = 1_000L),
            file?.durationFieldMs("durationSeconds", multiplier = 1_000L),
            file?.durationFieldMs("length", multiplier = 1_000L),
        )
    }

    private fun parseTags(raw: JsonObject): List<String> {
        return raw.optionalJsonArray("tags")
            ?.mapNotNull { element ->
                element.takeIf(JsonElement::isJsonObject)?.asJsonObject?.stringValue("id")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }
            .orEmpty()
    }

    private fun parseCreator(user: JsonObject?): CreatorProfile? {
        user ?: return null
        val displayName = user.stringValue("name")?.trim().takeUnless { it.isNullOrBlank() }
            ?: user.stringValue("username")?.trim().takeUnless { it.isNullOrBlank() }
            ?: return null
        val userId = user.stringValue("id")?.trim().takeUnless { it.isNullOrBlank() }
        val username = user.stringValue("username")?.trim().takeUnless { it.isNullOrBlank() }
        return CreatorProfile(
            source = SourceKey.IWARA,
            displayName = displayName,
            profileId = userId,
            profileUrl = username?.let { "https://www.iwara.tv/profile/$it/videos" },
            uploadsQuery = userId,
        )
    }

    private fun buildAssetUrl(asset: JsonObject): String? {
        val assetId = asset.stringValue("id")?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val fileExt = asset.stringValue("name")
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase()
            ?.ifBlank { "jpg" }
            ?: "jpg"
        return "https://i.iwara.tv/image/original/$assetId/$assetId.$fileExt"
    }

    private fun buildVideoThumbnailUrl(
        fileId: String,
        thumbnailIndex: Int?,
    ): String {
        val normalizedIndex = (thumbnailIndex ?: 0).coerceAtLeast(0)
        return "https://i.iwara.tv/image/thumbnail/$fileId/thumbnail-${normalizedIndex.toString().padStart(2, '0')}.jpg"
    }

    private fun buildEmbedThumbnailUrl(embedUrl: String): String? {
        val uri = runCatching { URI(embedUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty().removePrefix("www.")
        val videoId = when {
            host == "youtube.com" || host == "m.youtube.com" || host == "music.youtube.com" -> {
                uri.rawQuery
                    ?.split('&')
                    ?.mapNotNull { pair ->
                        val parts = pair.split('=', limit = 2)
                        val key = parts.getOrNull(0)?.trim()
                        val value = parts.getOrNull(1)?.trim()
                        if (key == "v" && !value.isNullOrBlank()) value else null
                    }
                    ?.firstOrNull()
            }
            host == "youtu.be" -> uri.path?.trim('/')?.takeIf(String::isNotBlank)
            else -> null
        } ?: return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
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

}

private data class ResolvedVariant(
    val name: String?,
    val mime: String?,
    val url: String,
)

private const val IWARA_API_BASE = "https://api.iwara.tv"
private const val JINA_MIRROR_BASE = "https://r.jina.ai/http://"
private val IWARA_REQUEST_HEADERS = mapOf(
    "Referer" to "https://www.iwara.tv/",
    "User-Agent" to "Mozilla/5.0",
    "Accept" to "application/json, text/plain, */*",
)
private val JINA_REQUEST_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0",
    "Accept" to "application/json, text/plain, */*",
)
