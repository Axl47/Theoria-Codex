package com.theoriacodex.sources.pixiv

import com.google.gson.Gson
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
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.common.asLongOrNull
import com.theoriacodex.sources.common.asStringOrNull
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.common.elementsOrEmpty
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.isSuccessful
import com.theoriacodex.sources.common.longValue
import com.theoriacodex.sources.common.optionalJsonArray
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.parseJsonObject
import com.theoriacodex.sources.common.sourceNetworkFailure
import com.theoriacodex.sources.common.sourceQuickQuery
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.media.inferMimeFromUrl
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
) : SourceAdapter, CreatorPostsSourceAdapter {
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
        val root = parseJsonObject(response, gson, "Pixiv")
        val illusts = root.optionalJsonArray("illusts")
        val posts = illusts?.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parseIllust)
        }.orEmpty()
        val normalizedPosts = if (query.sort == SortMode.RANDOM) posts.shuffled() else posts
        val nextToken = parseNextOffset(root.stringValue("next_url"))
        return Page(items = normalizedPosts, nextPageToken = nextToken)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
        val response = authorizedGet(
            url = "$PIXIV_API_BASE/v1/trending-tags/illust",
            query = mapOf("filter" to "for_android"),
        )
        val root = parseJsonObject(response, gson, "Pixiv")
        val tags = root.optionalJsonArray("trend_tags").elementsOrEmpty()
        return tags.mapNotNull { item ->
            val tag = item.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.optionalJsonObject("tag")
                ?: return@mapNotNull null
            TagSuggestion(
                text = tag.stringValue("name").orEmpty(),
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
        val root = parseJsonObject(response, gson, "Pixiv")
        val tags = root.optionalJsonArray("tags").elementsOrEmpty()
        return tags.mapNotNull { item ->
            val tag = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            TagSuggestion(
                text = tag.stringValue("name").orEmpty(),
                type = "tag",
                count = null,
            ).takeIf { it.text.isNotBlank() }
        }.take(limit)
    }

    override suspend fun quickQuery(kind: QuickQueryKind): Query {
        return sourceQuickQuery(
            source = SourceKey.PIXIV,
            kind = kind,
            nowEpochMs = clock(),
        )
    }

    override suspend fun resolvePost(id: PostId): Post? {
        if (id.source != SourceKey.PIXIV) return null
        val response = authorizedGet(
            url = "$PIXIV_API_BASE/v1/illust/detail",
            query = mapOf("illust_id" to id.sourcePostId),
        )
        val root = parseJsonObject(response, gson, "Pixiv")
        val illust = root.optionalJsonObject("illust") ?: return null
        val parsed = parseIllust(illust) ?: return null
        return if (illust.stringValue("type") == "ugoira" && parsed.durationMs == null) {
            parsed.copy(durationMs = fetchUgoiraDurationMs(id.sourcePostId))
        } else {
            parsed
        }
    }

    override suspend fun searchCreatorPosts(
        creator: CreatorProfile,
        pageToken: String?,
    ): Page<Post> {
        if (creator.source != SourceKey.PIXIV) return Page(items = emptyList(), nextPageToken = null)
        val userId = creator.uploadsQuery?.trim().takeIf { !it.isNullOrBlank() }
            ?: creator.profileId?.trim().takeIf { !it.isNullOrBlank() }
            ?: return Page(items = emptyList(), nextPageToken = null)
        val offset = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val params = linkedMapOf(
            "user_id" to userId,
            "type" to "illust",
            "filter" to "for_android",
        )
        if (offset > 0) {
            params["offset"] = offset.toString()
        }
        val response = authorizedGet(
            url = "$PIXIV_API_BASE/v1/user/illusts",
            query = params,
        )
        val root = parseJsonObject(response, gson, "Pixiv")
        val posts = root.optionalJsonArray("illusts")
            ?.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parseIllust)
            }
            .orEmpty()
        return Page(
            items = posts,
            nextPageToken = parseNextOffset(root.stringValue("next_url")),
        )
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
            sourceNetworkFailure("Pixiv network", error)
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
                sourceNetworkFailure("Pixiv retry", error)
            }
            if (!retry.isSuccessful()) {
                throw SourceAdapterException(
                    reason = mapHttpFailure(retry.statusCode),
                    message = "Pixiv request failed (${retry.statusCode})",
                )
            }
            return retry.body
        }

        if (!response.isSuccessful()) {
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

    private fun parseIllust(raw: JsonObject): Post? {
        val id = raw.longValue("id")?.toString() ?: return null
        val isUgoira = raw.stringValue("type") == "ugoira"
        val imageUrls = raw.optionalJsonObject("image_urls")
        val previewUrl = normalizedUrl(
            imageUrls?.stringValue("medium") ?: imageUrls?.stringValue("square_medium"),
        )
        val singlePageMediumUrl = normalizedUrl(imageUrls?.stringValue("medium"))
        val singlePageLargeUrl = normalizedUrl(imageUrls?.stringValue("large"))
        val singlePageOriginalUrl = normalizedUrl(
            raw.optionalJsonObject("meta_single_page")
                ?.stringValue("original_image_url"),
        )
        val fullUrl = singlePageOriginalUrl ?: singlePageLargeUrl ?: singlePageMediumUrl
        val fullMime = if (isUgoira) PIXIV_UGOIRA_MIME else inferMimeFromUrl(fullUrl)
        val previewMime = inferMimeFromUrl(previewUrl) ?: fullMime
        val previewRef = ImageRef(url = previewUrl, localPath = null, mime = previewMime)
        val singlePageRef = fullUrl?.let { canonicalUrl ->
            ImageRef(
                url = canonicalUrl,
                localPath = null,
                mime = fullMime,
                progressiveUrls = if (isUgoira) {
                    emptyList()
                } else {
                    buildPixivProgressiveUrls(
                        singlePageMediumUrl,
                        singlePageLargeUrl,
                        canonicalUrl = canonicalUrl,
                    )
                },
            )
        }
        val multiPageRefs = raw.optionalJsonArray("meta_pages").elementsOrEmpty().mapNotNull { page ->
            val pageObject = page.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val pageUrls = pageObject.optionalJsonObject("image_urls") ?: return@mapNotNull null
            val mediumUrl = normalizedUrl(pageUrls.stringValue("medium"))
            val largeUrl = normalizedUrl(pageUrls.stringValue("large"))
            val originalUrl = normalizedUrl(pageUrls.stringValue("original"))
            val pageUrl = originalUrl ?: largeUrl ?: mediumUrl ?: return@mapNotNull null
            ImageRef(
                url = pageUrl,
                localPath = null,
                mime = inferMimeFromUrl(pageUrl),
                progressiveUrls = buildPixivProgressiveUrls(
                    mediumUrl,
                    largeUrl,
                    canonicalUrl = pageUrl,
                ),
            )
        }
        val fallbackFullRef = singlePageRef
        val mediaRefs = when {
            multiPageRefs.isNotEmpty() -> multiPageRefs
            fallbackFullRef != null -> listOf(fallbackFullRef)
            !previewUrl.isNullOrBlank() -> listOf(previewRef)
            else -> emptyList()
        }

        val rawTags = mutableListOf<String>()
        val canonicalTags = linkedSetOf<String>()
        raw.optionalJsonArray("tags").elementsOrEmpty().forEach { tagElement ->
            val tagObject = tagElement.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val name = tagObject.stringValue("name")?.trim().orEmpty()
            if (name.isNotBlank()) {
                rawTags += name
                canonicalTags += name
            }
            val translatedName = tagObject.get("translated_name").asStringOrNull()
                ?.trim()
                .orEmpty()
            if (translatedName.isNotBlank()) {
                canonicalTags += translatedName
            }
        }
        val createdAt = parseIsoInstant(raw.stringValue("create_date"))
        val user = raw.optionalJsonObject("user")
        val userName = user?.stringValue("name")?.trim().orEmpty().ifBlank { null }
        val userId = user?.longValue("id")?.toString()
        val title = raw.stringValue("title")

        return Post(
            id = PostId(SourceKey.PIXIV, id),
            preview = previewRef,
            full = fallbackFullRef ?: mediaRefs.firstOrNull(),
            media = mediaRefs,
            pageUrl = "https://www.pixiv.net/en/artworks/$id",
            width = raw.intValue("width"),
            height = raw.intValue("height"),
            canonicalTags = canonicalTags.toList(),
            rawTags = rawTags.distinct(),
            authorName = userName,
            createdAtEpochMs = createdAt,
            title = title,
            creatorProfile = userName?.let { displayName ->
                CreatorProfile(
                    source = SourceKey.PIXIV,
                    displayName = displayName,
                    profileId = userId,
                    profileUrl = userId?.let { "https://www.pixiv.net/en/users/$it" },
                    uploadsQuery = userId,
                )
            },
            durationMs = null,
        )
    }

    private suspend fun fetchUgoiraDurationMs(postId: String): Long? {
        val response = authorizedGet(
            url = "$PIXIV_API_BASE/v1/ugoira/metadata",
            query = mapOf("illust_id" to postId),
        )
        val root = parseJsonObject(response, gson, "Pixiv")
        val metadata = root.optionalJsonObject("ugoira_metadata") ?: return null
        val frames = metadata.optionalJsonArray("frames").elementsOrEmpty()
        return frames.sumOf { frame ->
            frame.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("delay")
                .asLongOrNull()
                ?.coerceAtLeast(16L)
                ?: 0L
        }.takeIf { it > 0L }
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

private fun buildPixivProgressiveUrls(
    vararg candidateUrls: String?,
    canonicalUrl: String?,
): List<String> {
    return candidateUrls
        .mapNotNull(::normalizedUrl)
        .filter { it != canonicalUrl }
        .distinct()
}

private fun normalizedUrl(url: String?): String? {
    return url?.trim()?.takeIf(String::isNotBlank)
}

private fun parseNextOffset(nextUrl: String?): String? {
    if (nextUrl.isNullOrBlank()) return null
    val match = Regex("[?&]offset=(\\d+)").find(nextUrl) ?: return null
    return match.groupValues.getOrNull(1)
}

private fun mapHttpFailure(statusCode: Int): SourceFailureReason {
    return classifyHttpFailure(
        statusCode = statusCode,
        unauthorizedReason = SourceFailureReason.AUTH_EXPIRED,
        forbiddenReason = SourceFailureReason.AUTH_REQUIRED,
    )
}

private fun compileTagQuery(query: Query): String {
    val include = query.includeTags
        .map(::normalizePixivTagToken)
        .filter { it.isNotBlank() }
    val exclude = query.excludeTags
        .map(::normalizePixivTagToken)
        .filter { it.isNotBlank() }
        .map { "-$it" }
    return (include + exclude).joinToString(" ")
}

private fun normalizePixivTagToken(raw: String): String {
    var normalized = raw
        .trim()
        .removePrefix("-")
        .replace('_', ' ')
        .replace(PIXIV_TAG_WHITESPACE_REGEX, " ")
        .trim()
    while (normalized.isNotBlank() && PIXIV_TRAILING_PARENTHESIS_REGEX.containsMatchIn(normalized)) {
        normalized = normalized.replace(PIXIV_TRAILING_PARENTHESIS_REGEX, "").trim()
    }
    return normalized
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

private const val PIXIV_API_BASE: String = "https://app-api.pixiv.net"
const val PIXIV_UGOIRA_MIME: String = com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME
private val PIXIV_TRAILING_PARENTHESIS_REGEX = Regex("\\s*\\([^)]*\\)\\s*$")
private val PIXIV_TAG_WHITESPACE_REGEX = Regex("\\s+")
