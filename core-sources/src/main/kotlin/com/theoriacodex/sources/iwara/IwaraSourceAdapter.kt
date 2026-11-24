package com.theoriacodex.sources.iwara

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.AccountProfileSourceAdapter
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.common.canonicalPostPage
import com.theoriacodex.sources.common.durationFieldMs
import com.theoriacodex.sources.common.firstDurationMs
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.matchesChallenge
import com.theoriacodex.sources.common.optionalJsonArray
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.parseJsonElement
import com.theoriacodex.sources.common.parseJsonObject
import com.theoriacodex.sources.common.parseOffsetDateTimeMs
import com.theoriacodex.sources.common.requestWithChallengeFallback
import com.theoriacodex.sources.common.requireSuccess
import com.theoriacodex.sources.common.sourceCall
import com.theoriacodex.sources.common.sourceRequest
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.BROWSER_USER_AGENT
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.http.buildUrl
import com.theoriacodex.sources.media.inferMimeFromUrl
import java.net.URI

class IwaraSourceAdapter(
    private val httpClient: SourceHttpClient,
    private val gson: Gson = Gson(),
) : SourceAdapter, CreatorPostsSourceAdapter, AccountProfileSourceAdapter {
    override val sourceKey: SourceKey = SourceKey.IWARA

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = false,
        supportsExcludeTagsServerSide = false,
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> = sourceCall {
        val pageIndex = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val compiledQuery = compileQueryText(query)
        if (compiledQuery.isBlank()) return@sourceCall parsePagedPosts(videosPage(mapSort(query.sort), pageIndex))
        val response = requestJsonObject(
            url = "$IWARA_API_BASE/search",
            query = linkedMapOf(
                "type" to "videos",
                "page" to pageIndex.toString(),
                "query" to compiledQuery,
                "sort" to mapSort(query.sort),
            ),
        )
        parsePagedPosts(response)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = sourceCall {
        if (limit <= 0) return@sourceCall emptyList()
        val counts = linkedMapOf<String, Int>()
        parseResultItems(videosPage(sort = "date", page = 0)).forEach { item ->
            parseTags(item).forEach { tag ->
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        counts.entries
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

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = sourceCall {
        val normalized = prefix.trim()
        if (normalized.isBlank() || limit <= 0) return@sourceCall emptyList()
        val response = requestJsonObject(
            url = "$IWARA_API_BASE/tags",
            query = linkedMapOf(
                "query" to normalized,
                "page" to "0",
            ),
        )
        response.optionalJsonArray("results")
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

    override suspend fun resolvePost(id: PostId): Post? = sourceCall {
        if (id.source != SourceKey.IWARA) return@sourceCall null
        val response = request(
            url = "$IWARA_API_BASE/video/${id.sourcePostId}",
            query = emptyMap(),
        )
        if (response.statusCode == 404) return@sourceCall null
        val root = parseJsonObject(response.body, gson, "Iwara")
        val parsed = parseVideoPost(root) ?: return@sourceCall null
        val resolvedMedia = root.stringValue("fileUrl")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { fileUrl ->
                resolvePlayableVideoRef(
                    fileUrl = fileUrl,
                    fallbackMime = root.optionalJsonObject("file")?.stringValue("mime"),
                )
            }
            ?: return@sourceCall parsed
        parsed.copy(
            full = resolvedMedia,
            media = listOf(resolvedMedia),
        )
    }

    override suspend fun searchCreatorPosts(
        creator: CreatorProfile,
        pageToken: String?,
    ): Page<Post> = sourceCall {
        if (creator.source != SourceKey.IWARA) return@sourceCall canonicalPostPage(emptyList(), null)
        val userId = creator.uploadsQuery?.trim().takeUnless { it.isNullOrBlank() }
            ?: creator.profileId?.trim().takeUnless { it.isNullOrBlank() }
            ?: return@sourceCall canonicalPostPage(emptyList(), null)
        val pageIndex = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        parsePagedPosts(videosPage(sort = "date", page = pageIndex, user = userId))
    }

    override fun accountFeeds(creator: CreatorProfile): List<AccountFeed> =
        if (creator.source == SourceKey.IWARA && !creator.profileId.isNullOrBlank()) listOf(AccountFeed.UPLOADS) else emptyList()

    override suspend fun accountDetails(creator: CreatorProfile): AccountDetails? = sourceCall {
        val userId = creator.profileId?.trim()?.takeIf { it.isNotBlank() && creator.source == SourceKey.IWARA } ?: return@sourceCall null
        val username = iwaraUsername(creator) ?: return@sourceCall null
        val profile = request(url = "$IWARA_API_BASE/profile/$username", query = emptyMap())
        if (profile.statusCode == 404) return@sourceCall null
        val followers = requestJsonObject(url = "$IWARA_API_BASE/user/$userId/followers", query = mapOf("limit" to "1"))
        parseIwaraAccountDetails(parseJsonObject(profile.body, gson, "Iwara"), followers.intValue("count"))
    }

    override suspend fun accountPosts(creator: CreatorProfile, feed: AccountFeed, pageToken: String?): Page<Post> = sourceCall {
        when (feed) {
            AccountFeed.UPLOADS -> searchCreatorPosts(creator, pageToken)
            AccountFeed.FAVORITES -> canonicalPostPage(emptyList(), null)
        }
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

    private suspend fun videosPage(sort: String, page: Int, user: String? = null): JsonObject = requestJsonObject(
        url = "$IWARA_API_BASE/videos",
        query = buildMap {
            put("rating", "all")
            put("sort", sort)
            put("page", page.toString())
            user?.let { put("user", it) }
        },
    )

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
        val response = requestWithChallengeFallback("Iwara") {
            httpClient.get(
                url = url,
                query = query,
                headers = IWARA_REQUEST_HEADERS,
            )
        }
        val effectiveResponse = if (shouldUseMirrorFallback(response)) {
            requestMirror(url = url, query = query)
        } else {
            response
        }
        if (effectiveResponse.statusCode == 404) return effectiveResponse
        return effectiveResponse.requireSuccess("Iwara request failed")
    }

    private suspend fun requestMirror(
        url: String,
        query: Map<String, String>,
    ): SourceHttpResponse {
        val mirrorUrl = JINA_MIRROR_BASE + buildUrl(url, query).encodeJinaTargetUrl()
        val mirrored = sourceRequest("Iwara mirror") {
            httpClient.get(
                url = mirrorUrl,
                query = emptyMap(),
                headers = JINA_REQUEST_HEADERS,
            )
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
            videoVariants = variants.filter { variantPriority(it) >= 0 }.map { variant ->
                com.theoriacodex.domain.model.VideoVariant(
                    url = variant.url,
                    height = variantPriority(variant).takeIf { it in 1 until Int.MAX_VALUE },
                    original = variantPriority(variant) == Int.MAX_VALUE,
                    mime = variant.mime ?: inferMimeFromUrl(variant.url),
                )
            }.distinctBy { it.url },
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
        return canonicalPostPage(
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
            url = customThumbnail?.let(::iwaraAssetUrl)
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
            createdAtEpochMs = parseOffsetDateTimeMs(raw.stringValue("createdAt")),
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

}

internal fun iwaraAssetUrl(asset: JsonObject): String? {
    val assetId = asset.stringValue("id")?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val fileExt = asset.stringValue("name")
        ?.substringAfterLast('.', "")
        ?.trim()
        ?.lowercase()
        ?.ifBlank { "jpg" }
        ?: "jpg"
    return "https://i.iwara.tv/image/thumbnail/$assetId/$assetId.$fileExt"
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
    "User-Agent" to BROWSER_USER_AGENT,
    "Accept" to "application/json, text/plain, */*",
)
private val JINA_REQUEST_HEADERS = mapOf(
    "User-Agent" to BROWSER_USER_AGENT,
    "Accept" to "application/json, text/plain, */*",
)
