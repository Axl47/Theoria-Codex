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
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.media.inferMimeFromUrl
import com.theoriacodex.sources.media.mimeFromFileExt
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
        val parsed = parseVideoPost(root) ?: return null
        val resolvedMedia = root.string("fileUrl")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { fileUrl ->
                resolvePlayableVideoRef(
                    fileUrl = fileUrl,
                    fallbackMime = root.objectOrNull("file")?.string("mime"),
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
        return parseJsonObject(response.body)
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
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "Iwara request failed",
                cause = error,
            )
        }
        val effectiveResponse = if (shouldUseMirrorFallback(response)) {
            requestMirror(url = url, query = query)
        } else {
            response
        }
        if (effectiveResponse.statusCode !in 200..299 && effectiveResponse.statusCode != 404) {
            throw SourceAdapterException(
                reason = classifyFailure(effectiveResponse.statusCode),
                message = "Iwara request failed (${effectiveResponse.statusCode})",
            )
        }
        return effectiveResponse
    }

    private suspend fun requestMirror(
        url: String,
        query: Map<String, String>,
    ): SourceHttpResponse {
        val mirrorUrl = JINA_MIRROR_BASE + buildAbsoluteUrl(url, query)
        val mirrored = try {
            httpClient.get(
                url = mirrorUrl,
                query = emptyMap(),
                headers = JINA_REQUEST_HEADERS,
            )
        } catch (error: IOException) {
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "Iwara mirror request failed",
                cause = error,
            )
        }
        return mirrored.copy(body = extractJsonBody(mirrored.body))
    }

    private fun shouldUseMirrorFallback(response: SourceHttpResponse): Boolean {
        if (response.statusCode != 403) return false
        val challengeHeader = response.headers.entries.any { (name, values) ->
            name.equals("cf-mitigated", ignoreCase = true) &&
                values.any { value -> value.contains("challenge", ignoreCase = true) }
        }
        if (challengeHeader) return true
        val body = response.body.lowercase()
        return "cloudflare" in body || "cf-mitigated" in body
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

    private suspend fun resolvePlayableVideoRef(
        fileUrl: String,
        fallbackMime: String?,
    ): ImageRef? {
        val response = request(url = fileUrl, query = emptyMap())
        val root = parseJsonElement(response.body)
        if (!root.isJsonArray) return null
        val variants = root.asJsonArray.mapNotNull { element ->
            val variant = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val src = variant.objectOrNull("src")
            val resolvedUrl = src?.string("view")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(::normalizeVariantUrl)
                ?: src?.string("download")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::normalizeVariantUrl)
                ?: return@mapNotNull null
            ResolvedVariant(
                name = variant.string("name")?.trim(),
                mime = variant.string("type")?.trim(),
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

    private fun parseJsonElement(body: String): JsonElement {
        return runCatching { gson.fromJson(body, JsonElement::class.java) }.getOrNull()
            ?: throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "Iwara returned malformed JSON",
            )
    }

    private fun parseJsonObject(body: String): JsonObject {
        val root = parseJsonElement(body)
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
        val embedUrl = raw.string("embedUrl")?.trim().takeUnless { it.isNullOrBlank() }
        if (file == null && embedUrl != null) return null
        val preview = ImageRef(
            url = customThumbnail?.let(::buildAssetUrl)
                ?: file?.string("id")?.let { fileId ->
                    buildVideoThumbnailUrl(
                        fileId = fileId,
                        thumbnailIndex = raw.int("thumbnail"),
                    )
                }
                ?: embedUrl?.let(::buildEmbedThumbnailUrl),
            localPath = null,
            mime = customThumbnail?.string("mime") ?: "image/jpeg",
        )
        val tags = parseTags(raw)
        val creatorProfile = parseCreator(raw.objectOrNull("user"))
        return Post(
            id = PostId(source = SourceKey.IWARA, sourcePostId = sourcePostId),
            preview = preview,
            full = null,
            media = emptyList(),
            pageUrl = buildVideoPageUrl(sourcePostId = sourcePostId, slug = slug),
            width = file?.int("width") ?: customThumbnail?.int("width"),
            height = file?.int("height") ?: customThumbnail?.int("height"),
            canonicalTags = tags,
            rawTags = tags,
            authorName = creatorProfile?.displayName,
            createdAtEpochMs = raw.string("createdAt")?.let(::parseEpochMs),
            title = raw.string("title")?.trim().takeUnless { it.isNullOrBlank() },
            creatorProfile = creatorProfile,
            durationMs = parseIwaraDurationMs(raw, file),
        )
    }

    private fun parseIwaraDurationMs(raw: JsonObject, file: JsonObject?): Long? {
        return sequenceOf(
            raw.durationFieldMs("durationMs", multiplier = 1L),
            raw.durationFieldMs("duration", multiplier = 1_000L),
            raw.durationFieldMs("durationSeconds", multiplier = 1_000L),
            raw.durationFieldMs("length", multiplier = 1_000L),
            file?.durationFieldMs("durationMs", multiplier = 1L),
            file?.durationFieldMs("duration", multiplier = 1_000L),
            file?.durationFieldMs("durationSeconds", multiplier = 1_000L),
            file?.durationFieldMs("length", multiplier = 1_000L),
        ).filterNotNull().firstOrNull { it > 0L }
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

private fun JsonObject.durationFieldMs(name: String, multiplier: Long): Long? {
    val element = get(name)?.takeUnless { it.isJsonNull } ?: return null
    val raw = when {
        element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
            runCatching { element.asDouble }.getOrNull()?.let { number ->
                (number * multiplier).toLong()
            }
        }
        element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
            parseFlexibleDurationMs(element.asString, multiplier)
        }
        else -> null
    }
    return raw?.takeIf { it > 0L }
}

private fun parseFlexibleDurationMs(raw: String, numericMultiplier: Long): Long? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    trimmed.toDoubleOrNull()?.let { return (it * numericMultiplier).toLong() }
    val parts = trimmed.split(':').mapNotNull { part -> part.trim().toLongOrNull() }
    if (parts.isEmpty()) return null
    val seconds = when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] * 60L + parts[1]
        else -> parts.takeLast(3).let { (hours, minutes, seconds) ->
            hours * 3600L + minutes * 60L + seconds
        }
    }
    return seconds * 1_000L
}

private fun JsonObject.objectOrNull(name: String): JsonObject? {
    return get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
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
