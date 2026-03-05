package com.theoriacodex.sources.nhentai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.media.mimeFromFileExt
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NhentaiSourceAdapter(
    private val httpClient: SourceHttpClient,
    private val gson: Gson = Gson(),
    private val minRequestIntervalMs: Long = 350L,
) : SourceAdapter {
    override val sourceKey: SourceKey = SourceKey.NHENTAI

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = true,
        supportsExcludeTagsServerSide = true,
        supportsDateRangeServerSide = false,
        supportsMinScoreServerSide = false,
        requiresCredentials = false,
    )

    private val throttleMutex = Mutex()
    private var lastRequestAtEpochMs: Long = 0L

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        val pageIndex = pageToken?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val compiledQuery = compileNhentaiQuery(query)
        val params = linkedMapOf("page" to pageIndex.toString())
        val useAllEndpoint = compiledQuery.isBlank() &&
            (query.sort == SortMode.NEWEST || query.sort == SortMode.RANDOM)

        val url = if (useAllEndpoint) {
            NHENTAI_GALLERIES_ALL_URL
        } else {
            params["query"] = compiledQuery
            mapSortParam(query.sort)?.let { sort ->
                params["sort"] = sort
            }
            NHENTAI_GALLERIES_SEARCH_URL
        }

        val root = requireNotNull(requestJsonObject(url = url, query = params))
        val galleries = root.optionalJsonArray("result").orEmpty()
        val posts = galleries.mapNotNull { element ->
            element.takeIf(JsonElement::isJsonObject)
                ?.asJsonObject
                ?.let(::parseGallery)
        }
        val normalizedPosts = if (query.sort == SortMode.RANDOM) posts.shuffled() else posts

        val totalPages = root.get("num_pages").asIntOrNull()
        val nextPageToken = if (totalPages != null && pageIndex < totalPages) {
            (pageIndex + 1).toString()
        } else {
            null
        }

        return Page(
            items = normalizedPosts,
            nextPageToken = nextPageToken,
        )
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
        if (limit <= 0) return emptyList()
        val root = requireNotNull(requestJsonObject(
            url = NHENTAI_GALLERIES_ALL_URL,
            query = mapOf("page" to "1"),
        ))
        return collectTagSuggestions(
            galleries = root.optionalJsonArray("result").orEmpty(),
            prefix = null,
            limit = limit,
        )
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        val normalizedPrefix = prefix.trim()
        if (normalizedPrefix.isBlank() || limit <= 0) return emptyList()

        val root = requireNotNull(requestJsonObject(
            url = NHENTAI_GALLERIES_SEARCH_URL,
            query = mapOf(
                "query" to normalizedPrefix,
                "page" to "1",
                "sort" to "popular",
            ),
        ))
        return collectTagSuggestions(
            galleries = root.optionalJsonArray("result").orEmpty(),
            prefix = normalizedPrefix,
            limit = limit,
        )
    }

    override suspend fun quickQuery(kind: QuickQueryKind): Query {
        val sort = when (kind) {
            QuickQueryKind.POPULAR_TODAY -> SortMode.POPULAR
            QuickQueryKind.TOP_7D -> SortMode.TOP
            QuickQueryKind.TOP_30D -> SortMode.TOP
            QuickQueryKind.NEWEST -> SortMode.NEWEST
            QuickQueryKind.RANDOM -> SortMode.RANDOM
        }
        return Query(
            mode = QueryMode.Source(SourceKey.NHENTAI),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = sort,
            dateRange = null,
            minScore = null,
        )
    }

    override suspend fun resolvePost(id: PostId): Post? {
        if (id.source != SourceKey.NHENTAI) return null
        val galleryId = id.sourcePostId.trim().takeIf(String::isDigitsOnly) ?: return null

        val root = requestJsonObject(
            url = "$NHENTAI_GALLERY_URL_PREFIX/$galleryId",
            allowNotFound = true,
        ) ?: return null
        return parseGallery(root)
    }

    private suspend fun requestJsonObject(
        url: String,
        query: Map<String, String> = emptyMap(),
        allowNotFound: Boolean = false,
    ): JsonObject? {
        throttle()

        val response = try {
            httpClient.get(
                url = url,
                query = query,
                headers = NHENTAI_DEFAULT_HEADERS,
            )
        } catch (error: IOException) {
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "NHentai request failed",
                cause = error,
            )
        }

        if (allowNotFound && response.statusCode == 404) {
            return null
        }

        if (response.statusCode !in 200..299) {
            throw SourceAdapterException(
                reason = mapHttpFailure(response.statusCode),
                message = "NHentai request failed (${response.statusCode})",
            )
        }

        return parseJsonObject(response.body)
    }

    private suspend fun throttle() {
        throttleMutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = minRequestIntervalMs - (now - lastRequestAtEpochMs)
            if (waitMs > 0L) {
                delay(waitMs)
            }
            lastRequestAtEpochMs = System.currentTimeMillis()
        }
    }

    private fun parseJsonObject(body: String): JsonObject {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<")) {
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "NHentai returned non-JSON response (possibly blocked)",
            )
        }

        return runCatching { gson.fromJson(trimmed, JsonObject::class.java) }.getOrNull()
            ?: throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "NHentai returned malformed JSON",
            )
    }

    private fun parseGallery(raw: JsonObject): Post? {
        val galleryId = raw.get("id").asLongOrNull()?.toString()
            ?: raw.get("id")?.asString?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val mediaId = raw.get("media_id").asLongOrNull()?.toString()
            ?: raw.get("media_id")?.asString?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val images = raw.optionalJsonObject("images") ?: return null
        val thumbnail = images.optionalJsonObject("thumbnail")
        val cover = images.optionalJsonObject("cover")
        val pages = images.optionalJsonArray("pages").orEmpty()

        val thumbExt = imageExtension(thumbnail?.get("t")?.asString)
        val coverExt = imageExtension(cover?.get("t")?.asString)
        val previewUrl = "$NHENTAI_THUMBS_BASE/galleries/$mediaId/thumb.$thumbExt"
        val coverUrl = "$NHENTAI_THUMBS_BASE/galleries/$mediaId/cover.$coverExt"

        val pageRefs = pages.mapIndexedNotNull { index, pageElement ->
            val page = pageElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapIndexedNotNull null
            val ext = imageExtension(page.get("t")?.asString)
            ImageRef(
                url = "$NHENTAI_IMAGES_BASE/galleries/$mediaId/${index + 1}.$ext",
                localPath = null,
                mime = mimeFromFileExt(ext),
            )
        }

        val fallbackCoverRef = ImageRef(
            url = coverUrl,
            localPath = null,
            mime = mimeFromFileExt(coverExt),
        )

        val mediaRefs = if (pageRefs.isNotEmpty()) {
            pageRefs
        } else {
            listOf(fallbackCoverRef)
        }

        val fullRef = mediaRefs.firstOrNull()
        val previewRef = ImageRef(
            url = previewUrl,
            localPath = null,
            mime = mimeFromFileExt(thumbExt) ?: fullRef?.mime,
        )

        val tags = raw.optionalJsonArray("tags").orEmpty().mapNotNull { tagElement ->
            val tag = tagElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val name = tag.get("name")?.asString?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            ParsedNhentaiTag(
                name = name,
                type = tag.get("type")?.asString?.trim(),
            )
        }

        val canonicalTags = tags.map { it.name }.distinctBy { it.lowercase() }
        val rawTags = canonicalTags
        val titleObject = raw.optionalJsonObject("title")
        val title = listOf(
            titleObject?.get("pretty")?.asString,
            titleObject?.get("english")?.asString,
            titleObject?.get("japanese")?.asString,
        ).firstOrNull { !it.isNullOrBlank() }

        val scanlator = raw.get("scanlator")?.asString?.trim().orEmpty()
        val artist = tags.firstOrNull { it.type.equals("artist", ignoreCase = true) }?.name
        val authorName = scanlator.ifBlank { artist }
        val createdAtEpochMs = raw.get("upload_date").asLongOrNull()?.times(1000L)

        return Post(
            id = PostId(SourceKey.NHENTAI, galleryId),
            preview = previewRef,
            full = fullRef,
            media = mediaRefs,
            pageUrl = "https://nhentai.net/g/$galleryId/",
            width = thumbnail?.get("w").asIntOrNull() ?: cover?.get("w").asIntOrNull(),
            height = thumbnail?.get("h").asIntOrNull() ?: cover?.get("h").asIntOrNull(),
            canonicalTags = canonicalTags,
            rawTags = rawTags,
            authorName = authorName,
            createdAtEpochMs = createdAtEpochMs,
            title = title?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun collectTagSuggestions(
        galleries: List<JsonElement>,
        prefix: String?,
        limit: Int,
    ): List<TagSuggestion> {
        if (limit <= 0) return emptyList()
        val normalizedPrefix = prefix?.trim()?.lowercase().orEmpty()
        val byName = linkedMapOf<String, TagSuggestion>()

        galleries.forEach galleriesLoop@{ galleryElement ->
            val gallery = galleryElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@galleriesLoop
            val tags = gallery.optionalJsonArray("tags").orEmpty()
            tags.forEach tagLoop@{ tagElement ->
                val tag = tagElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@tagLoop
                val name = tag.get("name")?.asString?.trim().orEmpty()
                if (name.isBlank()) return@tagLoop
                if (normalizedPrefix.isNotBlank() && !name.lowercase().contains(normalizedPrefix)) {
                    return@tagLoop
                }

                val key = name.lowercase()
                val count = tag.get("count").asIntOrNull()
                val type = tag.get("type")?.asString?.trim()?.takeIf { it.isNotBlank() }
                val existing = byName[key]
                val existingCount = existing?.count ?: Int.MIN_VALUE
                val candidateCount = count ?: Int.MIN_VALUE

                if (existing == null || candidateCount > existingCount) {
                    byName[key] = TagSuggestion(
                        text = name,
                        type = type,
                        count = count,
                    )
                }
            }
        }

        return byName.values
            .sortedWith(
                compareByDescending<TagSuggestion> { suggestion -> suggestion.count ?: Int.MIN_VALUE }
                    .thenBy { suggestion -> suggestion.text.lowercase() }
            )
            .take(limit)
    }
}

private data class ParsedNhentaiTag(
    val name: String,
    val type: String?,
)

private fun compileNhentaiQuery(query: Query): String {
    val include = query.includeTags
        .map(::normalizeNhentaiTag)
        .filter { it.isNotBlank() }
    val exclude = query.excludeTags
        .map(::normalizeNhentaiTag)
        .filter { it.isNotBlank() }
        .map { "-$it" }
    return (include + exclude).take(40).joinToString(" ")
}

private fun normalizeNhentaiTag(value: String): String {
    return value
        .trim()
        .removePrefix("-")
        .replace('_', ' ')
        .replace(NHENTAI_WHITESPACE_REGEX, " ")
        .trim()
}

private fun mapSortParam(sortMode: SortMode): String? {
    return when (sortMode) {
        SortMode.NEWEST -> null
        SortMode.POPULAR -> "popular-today"
        SortMode.TOP -> "popular-week"
        SortMode.RANDOM -> null
    }
}

private fun mapHttpFailure(statusCode: Int): SourceFailureReason {
    return when (statusCode) {
        429 -> SourceFailureReason.RATE_LIMITED
        in 500..599 -> SourceFailureReason.NETWORK
        else -> SourceFailureReason.UNKNOWN
    }
}

private fun imageExtension(type: String?): String {
    return when (type?.trim()?.lowercase()) {
        "p", "png" -> "png"
        "g", "gif" -> "gif"
        else -> "jpg"
    }
}

private fun JsonObject.optionalJsonArray(name: String): JsonArray? {
    return get(name)?.takeIf { it.isJsonArray }?.asJsonArray
}

private fun JsonObject.optionalJsonObject(name: String): JsonObject? {
    return get(name)?.takeIf { it.isJsonObject }?.asJsonObject
}

private fun JsonArray?.orEmpty(): List<JsonElement> {
    return this?.toList().orEmpty()
}

private fun JsonElement?.asIntOrNull(): Int? {
    if (this == null || this.isJsonNull) return null
    val primitive = this.asJsonPrimitive
    return runCatching {
        when {
            primitive.isNumber -> primitive.asInt
            primitive.isString -> primitive.asString.trim().toInt()
            else -> null
        }
    }.getOrNull()
}

private fun JsonElement?.asLongOrNull(): Long? {
    if (this == null || this.isJsonNull) return null
    val primitive = this.asJsonPrimitive
    return runCatching {
        when {
            primitive.isNumber -> primitive.asLong
            primitive.isString -> primitive.asString.trim().toLong()
            else -> null
        }
    }.getOrNull()
}

private fun String.isDigitsOnly(): Boolean {
    return isNotBlank() && all { ch -> ch.isDigit() }
}

private const val NHENTAI_GALLERIES_ALL_URL = "https://nhentai.net/api/galleries/all"
private const val NHENTAI_GALLERIES_SEARCH_URL = "https://nhentai.net/api/galleries/search"
private const val NHENTAI_GALLERY_URL_PREFIX = "https://nhentai.net/api/gallery"
private const val NHENTAI_IMAGES_BASE = "https://i.nhentai.net"
private const val NHENTAI_THUMBS_BASE = "https://t.nhentai.net"
private val NHENTAI_DEFAULT_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0",
    "Referer" to "https://nhentai.net/",
)
private val NHENTAI_WHITESPACE_REGEX = Regex("\\s+")
