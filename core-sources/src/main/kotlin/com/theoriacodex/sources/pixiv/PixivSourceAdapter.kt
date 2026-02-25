package com.theoriacodex.sources.pixiv

import com.google.gson.Gson
import com.google.gson.JsonArray
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
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PixivSourceAdapter(
    private val httpClient: SourceHttpClient,
    private val credentialsProvider: SourceCredentialsProvider,
    private val authApi: PixivAuthApi = PixivAuthApi(httpClient),
    private val gson: Gson = Gson(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val minRequestIntervalMs: Long = 350L,
) : SourceAdapter {
    override val sourceKey: SourceKey = SourceKey.PIXIV

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = true,
        supportsExcludeTagsServerSide = false,
        supportsDateRangeServerSide = true,
        supportsMinScoreServerSide = false,
        requiresCredentials = true,
    )

    private val throttleMutex = Mutex()
    private var lastRequestAtEpochMs: Long = 0L

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        val offset = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val params = mutableMapOf(
            "word" to compileTagQuery(query),
            "search_target" to "partial_match_for_tags",
            "sort" to mapSort(query.sort),
            "filter" to "for_android",
        )
        if (offset > 0) {
            params["offset"] = offset.toString()
        }
        query.dateRange?.let { range ->
            range.fromEpochMs?.let { params["start_date"] = formatEpochDate(it) }
            range.toEpochMs?.let { params["end_date"] = formatEpochDate(it) }
        }

        val response = authorizedGet(
            url = "$PIXIV_API_BASE/v1/search/illust",
            query = params,
        )
        val root = parseJsonObject(response)
        val illusts = root.optionalJsonArray("illusts")
        val posts = illusts?.mapNotNull { parseIllust(it.asJsonObject) }.orEmpty()
        val normalizedPosts = if (query.sort == SortMode.RANDOM) posts.shuffled() else posts
        val nextToken = parseNextOffset(root.get("next_url")?.asString)
        return Page(items = normalizedPosts, nextPageToken = nextToken)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
        val response = authorizedGet(
            url = "$PIXIV_API_BASE/v1/trending-tags/illust",
            query = mapOf("filter" to "for_android"),
        )
        val root = parseJsonObject(response)
        val tags = root.optionalJsonArray("trend_tags").orEmpty()
        return tags.mapNotNull { item ->
            val tag = item.asJsonObject.optionalJsonObject("tag") ?: return@mapNotNull null
            TagSuggestion(
                text = tag.get("name")?.asString.orEmpty(),
                type = "trending",
                count = null,
            ).takeIf { it.text.isNotBlank() }
        }.take(limit)
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        if (prefix.isBlank()) return emptyList()
        val response = authorizedGet(
            url = "$PIXIV_API_BASE/v2/search/autocomplete",
            query = mapOf("word" to prefix),
        )
        val root = parseJsonObject(response)
        val tags = root.optionalJsonArray("tags").orEmpty()
        return tags.mapNotNull { item ->
            val tag = item.asJsonObject
            TagSuggestion(
                text = tag.get("name")?.asString.orEmpty(),
                type = "tag",
                count = null,
            ).takeIf { it.text.isNotBlank() }
        }.take(limit)
    }

    override suspend fun quickQuery(kind: QuickQueryKind): Query {
        val now = clock()
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
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = sort,
            dateRange = dateRange,
            minScore = null,
        )
    }

    override suspend fun resolvePost(id: PostId): Post? {
        if (id.source != SourceKey.PIXIV) return null
        val response = authorizedGet(
            url = "$PIXIV_API_BASE/v1/illust/detail",
            query = mapOf("illust_id" to id.sourcePostId),
        )
        val root = parseJsonObject(response)
        val illust = root.optionalJsonObject("illust") ?: return null
        return parseIllust(illust)
    }

    private suspend fun authorizedGet(
        url: String,
        query: Map<String, String>,
    ): String {
        throttle()
        val currentTokens = activeTokens()
        val response = try {
            httpClient.get(
                url = url,
                query = query,
                headers = mapOf("Authorization" to "Bearer ${currentTokens.accessToken}"),
            )
        } catch (error: IOException) {
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "Pixiv network request failed",
                cause = error,
            )
        }

        if (response.statusCode == 401 || response.statusCode == 403) {
            val refreshed = refreshTokens(currentTokens.refreshToken)
            val retry = try {
                httpClient.get(
                    url = url,
                    query = query,
                    headers = mapOf("Authorization" to "Bearer ${refreshed.accessToken}"),
                )
            } catch (error: IOException) {
                throw SourceAdapterException(
                    reason = SourceFailureReason.NETWORK,
                    message = "Pixiv retry request failed",
                    cause = error,
                )
            }
            if (retry.statusCode !in 200..299) {
                throw SourceAdapterException(
                    reason = mapHttpFailure(retry.statusCode),
                    message = "Pixiv request failed (${retry.statusCode})",
                )
            }
            return retry.body
        }

        if (response.statusCode !in 200..299) {
            throw SourceAdapterException(
                reason = mapHttpFailure(response.statusCode),
                message = "Pixiv request failed (${response.statusCode})",
            )
        }

        return response.body
    }

    private suspend fun activeTokens(): PixivAuthTokens {
        val current = credentialsProvider.getPixivTokens()
            ?: throw SourceAdapterException(
                reason = SourceFailureReason.AUTH_REQUIRED,
                message = "Pixiv credentials not configured",
            )

        return if (clock() + 60_000L < current.expiresAtEpochMs) {
            current
        } else {
            refreshTokens(current.refreshToken)
        }
    }

    private suspend fun refreshTokens(refreshToken: String): PixivAuthTokens {
        val refreshed = authApi.refresh(refreshToken)
        credentialsProvider.savePixivTokens(refreshed)
        return refreshed
    }

    private suspend fun throttle() {
        throttleMutex.withLock {
            val now = clock()
            val waitMs = minRequestIntervalMs - (now - lastRequestAtEpochMs)
            if (waitMs > 0L) {
                delay(waitMs)
            }
            lastRequestAtEpochMs = clock()
        }
    }

    private fun parseJsonObject(body: String): JsonObject {
        val parsed = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
        return parsed ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "Pixiv response was not valid JSON",
        )
    }

    private fun parseIllust(raw: JsonObject): Post? {
        val id = raw.get("id")?.asLong?.toString() ?: return null
        val isUgoira = raw.get("type")?.asString == "ugoira"
        val imageUrls = raw.optionalJsonObject("image_urls")
        val previewUrl = imageUrls?.get("square_medium")?.asString ?: imageUrls?.get("medium")?.asString
        val fullUrl = raw.optionalJsonObject("meta_single_page")
            ?.get("original_image_url")
            ?.asString
            ?: imageUrls?.get("large")?.asString
        val fullMime = if (isUgoira) PIXIV_UGOIRA_MIME else inferMimeFromUrl(fullUrl)
        val previewMime = inferMimeFromUrl(previewUrl) ?: fullMime
        val previewRef = ImageRef(url = previewUrl, localPath = null, mime = previewMime)
        val multiPageRefs = raw.optionalJsonArray("meta_pages").orEmpty().mapNotNull { page ->
            val pageUrls = page.asJsonObject.optionalJsonObject("image_urls") ?: return@mapNotNull null
            val pageUrl = pageUrls.get("original")?.asString
                ?: pageUrls.get("large")?.asString
                ?: pageUrls.get("medium")?.asString
                ?: return@mapNotNull null
            ImageRef(
                url = pageUrl,
                localPath = null,
                mime = inferMimeFromUrl(pageUrl),
            )
        }
        val fallbackFullRef = fullUrl?.let { ImageRef(url = it, localPath = null, mime = fullMime) }
        val mediaRefs = when {
            multiPageRefs.isNotEmpty() -> multiPageRefs
            fallbackFullRef != null -> listOf(fallbackFullRef)
            !previewUrl.isNullOrBlank() -> listOf(previewRef)
            else -> emptyList()
        }

        val tags = raw.optionalJsonArray("tags").orEmpty().mapNotNull { tagElement ->
            tagElement.asJsonObject.get("name")?.asString
        }
        val createdAt = parseIsoInstant(raw.get("create_date")?.asString)
        val userName = raw.optionalJsonObject("user")?.get("name")?.asString
        val title = raw.get("title")?.asString

        return Post(
            id = PostId(SourceKey.PIXIV, id),
            preview = previewRef,
            full = fallbackFullRef ?: mediaRefs.firstOrNull(),
            media = mediaRefs,
            pageUrl = "https://www.pixiv.net/en/artworks/$id",
            width = raw.get("width")?.asInt,
            height = raw.get("height")?.asInt,
            canonicalTags = tags,
            rawTags = tags,
            authorName = userName,
            createdAtEpochMs = createdAt,
            title = title,
        )
    }

    private fun mapSort(sortMode: SortMode): String {
        return when (sortMode) {
            SortMode.NEWEST -> "date_desc"
            SortMode.POPULAR -> "popular_desc"
            SortMode.TOP -> "popular_desc"
            SortMode.RANDOM -> "date_desc"
        }
    }
}

private fun inferMimeFromUrl(url: String?): String? {
    val normalized = url?.substringBefore('?')?.lowercase() ?: return null
    return when {
        normalized.endsWith(".gif") -> "image/gif"
        normalized.endsWith(".png") -> "image/png"
        normalized.endsWith(".webp") -> "image/webp"
        normalized.endsWith(".jpg") || normalized.endsWith(".jpeg") -> "image/jpeg"
        else -> null
    }
}

private fun parseNextOffset(nextUrl: String?): String? {
    if (nextUrl.isNullOrBlank()) return null
    val match = Regex("[?&]offset=(\\d+)").find(nextUrl) ?: return null
    return match.groupValues.getOrNull(1)
}

private fun mapHttpFailure(statusCode: Int): SourceFailureReason {
    return when (statusCode) {
        401 -> SourceFailureReason.AUTH_EXPIRED
        403 -> SourceFailureReason.AUTH_REQUIRED
        429 -> SourceFailureReason.RATE_LIMITED
        in 500..599 -> SourceFailureReason.NETWORK
        else -> SourceFailureReason.UNKNOWN
    }
}

private fun compileTagQuery(query: Query): String {
    val include = query.includeTags.map { it.trim() }.filter { it.isNotBlank() }
    val exclude = query.excludeTags.map { it.trim() }.filter { it.isNotBlank() }.map { "-$it" }
    return (include + exclude).joinToString(" ")
}

private fun parseIsoInstant(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

private fun formatEpochDate(value: Long): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochMilli(value))
}

private fun JsonObject.optionalJsonArray(name: String): JsonArray? {
    return get(name)?.takeIf { it.isJsonArray }?.asJsonArray
}

private fun JsonObject.optionalJsonObject(name: String): JsonObject? {
    return get(name)?.takeIf { it.isJsonObject }?.asJsonObject
}

private fun JsonArray?.orEmpty(): List<com.google.gson.JsonElement> {
    return this?.toList().orEmpty()
}

private const val PIXIV_API_BASE: String = "https://app-api.pixiv.net"
const val PIXIV_UGOIRA_MIME: String = "image/ugoira"
