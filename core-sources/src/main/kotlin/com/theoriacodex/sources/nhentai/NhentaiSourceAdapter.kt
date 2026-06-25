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
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.media.mimeFromFileExt
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup

class NhentaiSourceAdapter(
    private val httpClient: SourceHttpClient,
    private val gson: Gson = Gson(),
    private val minRequestIntervalMs: Long = 350L,
) : SourceAdapter, TagCountLookupSourceAdapter {
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
    private val tagInfoByKey = mutableMapOf<String, NhentaiTagInfo>()

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        val directGalleryId = query.directNhentaiGalleryIdCandidate()
        if (directGalleryId != null) {
            val resolved = resolvePost(
                PostId(
                    source = SourceKey.NHENTAI,
                    sourcePostId = directGalleryId,
                )
            )
            return Page(
                items = listOfNotNull(resolved),
                nextPageToken = null,
            )
        }

        val pageIndex = pageToken?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val compiledQuery = compileNhentaiQuery(query)
        val params = linkedMapOf("page" to pageIndex.toString())
        val exactTag = if (query.sort != SortMode.RANDOM) {
            query.singleIncludeTagCandidate()?.let { tag ->
                runCatching { resolveNhentaiTag(tag) }.getOrNull()
            }
        } else {
            null
        }
        val useAllEndpoint = compiledQuery.isBlank() &&
            (query.sort == SortMode.NEWEST || query.sort == SortMode.RANDOM)

        val url = if (exactTag != null) {
            params["tag_id"] = exactTag.id.toString()
            mapTaggedSortParam(query.sort)?.let { sort ->
                params["sort"] = sort
            }
            NHENTAI_GALLERIES_TAGGED_URL
        } else if (useAllEndpoint) {
            NHENTAI_GALLERIES_ALL_URL
        } else {
            params["query"] = compiledQuery.ifBlank { NHENTAI_V2_SEARCH_ALL_QUERY }
            mapSortParam(query.sort)?.let { sort ->
                params["sort"] = sort
            }
            NHENTAI_GALLERIES_SEARCH_URL
        }

        val root = requestJsonObjectOrMirrorSearch(
            url = url,
            query = params,
            compiledQuery = compiledQuery,
            sort = query.sort,
            pageIndex = pageIndex,
            useAllEndpoint = useAllEndpoint,
        )
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

        return requestTagSearch(normalizedPrefix)
            .map(::tagSuggestion)
            .take(limit)
    }

    override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> {
        val requested = tags
            .asSequence()
            .map(::normalizeNhentaiTag)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (requested.isEmpty()) return emptyMap()

        val counts = linkedMapOf<String, Int>()
        requested.forEach { tag ->
            val info = resolveNhentaiTag(tag)
            if (info != null) {
                counts[info.name] = info.count
            }
        }
        return counts
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

        val response = requestDirect(
            url = "$NHENTAI_GALLERY_URL_PREFIX/$galleryId",
            query = emptyMap(),
        )
        if (response.statusCode == 404) return null
        if (shouldUseMirrorFallback(response)) {
            return resolvePostFromMirrorPage(galleryId)
        }
        if (response.statusCode !in 200..299) {
            throw SourceAdapterException(
                reason = mapHttpFailure(response.statusCode),
                message = "NHentai request failed (${response.statusCode})",
            )
        }

        val root = parseJsonObject(response.body)
        return parseGallery(root)
    }

    private suspend fun requestJsonObjectOrMirrorSearch(
        url: String,
        query: Map<String, String>,
        compiledQuery: String,
        sort: SortMode,
        pageIndex: Int,
        useAllEndpoint: Boolean,
    ): JsonObject {
        val response = requestDirect(url = url, query = query)
        if (shouldUseMirrorFallback(response)) {
            return requestMirrorSearch(
                compiledQuery = compiledQuery,
                sort = sort,
                pageIndex = pageIndex,
                useAllEndpoint = useAllEndpoint,
            )
        }
        if (response.statusCode !in 200..299) {
            throw SourceAdapterException(
                reason = mapHttpFailure(response.statusCode),
                message = "NHentai request failed (${response.statusCode})",
            )
        }
        return parseJsonObject(response.body)
    }

    private suspend fun requestJsonObject(
        url: String,
        query: Map<String, String> = emptyMap(),
        allowNotFound: Boolean = false,
    ): JsonObject? {
        val response = requestDirect(url = url, query = query)

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

    private suspend fun requestJsonArrayPost(
        url: String,
        body: String,
    ): JsonArray {
        val response = requestJsonPost(url = url, body = body)
        if (response.statusCode !in 200..299) {
            throw SourceAdapterException(
                reason = mapHttpFailure(response.statusCode),
                message = "NHentai request failed (${response.statusCode})",
            )
        }
        return parseJsonArray(response.body)
    }

    private suspend fun requestDirect(
        url: String,
        query: Map<String, String>,
    ): SourceHttpResponse {
        throttle()
        return try {
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
    }

    private suspend fun requestJsonPost(
        url: String,
        body: String,
    ): SourceHttpResponse {
        throttle()
        return try {
            httpClient.postJson(
                url = url,
                body = body,
                headers = NHENTAI_JSON_POST_HEADERS,
            )
        } catch (error: IOException) {
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "NHentai request failed",
                cause = error,
            )
        }
    }

    private suspend fun requestTagSearch(query: String): List<NhentaiTagInfo> {
        val normalizedQuery = normalizeNhentaiTag(query)
        if (normalizedQuery.isBlank()) return emptyList()
        tagInfoByKey[tagCacheKey(normalizedQuery)]?.let { return listOf(it) }

        val body = gson.toJson(
            mapOf(
                "query" to normalizedQuery,
            )
        )
        return requestJsonArrayPost(
            url = NHENTAI_TAGS_SEARCH_URL,
            body = body,
        ).mapNotNull { element ->
            element.takeIf(JsonElement::isJsonObject)
                ?.asJsonObject
                ?.let(::parseTagInfo)
        }.also { tags ->
            tags.forEach(::cacheTagInfo)
        }
    }

    private suspend fun resolveNhentaiTag(tag: String): NhentaiTagInfo? {
        val normalized = normalizeNhentaiTag(tag)
        if (normalized.isBlank()) return null
        tagInfoByKey[tagCacheKey(normalized)]?.let { return it }
        val normalizedSlug = normalized.toNhentaiSlug()
        return requestTagSearch(normalized)
            .firstOrNull { info ->
                info.name.equals(normalized, ignoreCase = true) ||
                    info.slug.equals(normalizedSlug, ignoreCase = true)
            }
    }

    private suspend fun requestMirrorSearch(
        compiledQuery: String,
        sort: SortMode,
        pageIndex: Int,
        useAllEndpoint: Boolean,
    ): JsonObject {
        val pathAndQuery = if (useAllEndpoint) {
            "/?page=$pageIndex"
        } else {
            val params = linkedMapOf(
                "q" to compiledQuery,
                "page" to pageIndex.toString(),
            )
            mapMirrorSortParam(sort)?.let { params["sort"] = it }
            "/search/?" + params.entries.joinToString("&") { (key, value) ->
                "${key.urlEncode()}=${value.urlEncode()}"
            }
        }
        val body = requestMirror(pathAndQuery)
        return parseMirrorSearchPage(body)
    }

    private suspend fun resolvePostFromMirrorPage(galleryId: String): Post? {
        val body = requestMirror("/g/$galleryId/1/")
        val mediaId = NHENTAI_MIRROR_PAGE_IMAGE_REGEX.find(body)?.groupValues?.getOrNull(1)
            ?: return null
        val firstPageExt = NHENTAI_MIRROR_PAGE_IMAGE_REGEX.find(body)?.groupValues?.getOrNull(2)
            ?.lowercase()
            ?: "webp"
        val pageCount = NHENTAI_MIRROR_PAGE_COUNT_REGEX.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 1
        val title = parseMirrorTitle(body)
            ?.removeSuffix(" - Page 1")
            ?.removeSuffix(" » nhentai")
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val galleryBase = "$NHENTAI_IMAGES_BASE/galleries/$mediaId"
        val mediaRefs = (1..pageCount).map { page ->
            val primaryUrl = "$galleryBase/$page.$firstPageExt"
            ImageRef(
                url = primaryUrl,
                localPath = null,
                mime = mimeFromFileExt(firstPageExt),
                progressiveUrls = imageUrlCandidates(galleryBase, page, firstPageExt),
            )
        }
        val preview = ImageRef(
            url = "$NHENTAI_THUMBS_BASE/galleries/$mediaId/thumb.$firstPageExt",
            localPath = null,
            mime = mimeFromFileExt(firstPageExt),
        )
        val post = Post(
            id = PostId(SourceKey.NHENTAI, galleryId),
            preview = preview,
            full = mediaRefs.firstOrNull(),
            media = mediaRefs,
            pageUrl = "https://nhentai.net/g/$galleryId/",
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = title,
        )
        val metadata = fetchMirrorGalleryMetadata(galleryId)
        return if (metadata != null && metadata.canonicalTags.isNotEmpty()) {
            post.copy(
                canonicalTags = metadata.canonicalTags,
                rawTags = metadata.canonicalTags,
                authorName = metadata.authorName,
            )
        } else {
            post
        }
    }

    private suspend fun fetchMirrorGalleryMetadata(galleryId: String): NhentaiMirrorMetadata? {
        NHENTAI_METADATA_MIRROR_BASES.forEach { baseUrl ->
            val response = requestMetadataMirror("$baseUrl/g/$galleryId/")
            if (response != null && response.statusCode in 200..299) {
                parseMirrorGalleryMetadata(response.body)?.let { metadata ->
                    if (metadata.canonicalTags.isNotEmpty()) return metadata
                }
            }
        }
        return null
    }

    private suspend fun requestMetadataMirror(url: String): SourceHttpResponse? {
        throttle()
        return runCatching {
            httpClient.get(
                url = url,
                query = emptyMap(),
                headers = NHENTAI_METADATA_MIRROR_HEADERS,
            )
        }.getOrNull()
    }

    private fun parseMirrorGalleryMetadata(body: String): NhentaiMirrorMetadata? {
        val document = Jsoup.parse(body)
        val tagLinks = document.select(
            "section#tags a.tag, li.tags a.tag_btn"
        )
        val tags = tagLinks
            .mapNotNull { link ->
                val href = link.attr("href").trim()
                if (!href.isNhentaiTaxonomyPath()) return@mapNotNull null
                val name = link.selectFirst(".name")?.text()?.trim()
                    ?: link.ownText().trim()
                name.takeIf(String::isNotBlank)
            }
            .distinctBy { it.lowercase() }
        if (tags.isEmpty()) return null

        val authorName = tagLinks
            .firstOrNull { link -> link.attr("href").trim().startsWith("/artist/") }
            ?.let { link ->
                link.selectFirst(".name")?.text()?.trim()
                    ?: link.ownText().trim()
            }
            ?.takeIf(String::isNotBlank)
        return NhentaiMirrorMetadata(
            canonicalTags = tags,
            authorName = authorName,
        )
    }

    private suspend fun requestMirror(pathAndQuery: String): String {
        val mirrorUrl = JINA_MIRROR_BASE + NHENTAI_WEB_BASE + pathAndQuery.removePrefix("/")
        val response = try {
            httpClient.get(
                url = mirrorUrl,
                query = emptyMap(),
                headers = JINA_REQUEST_HEADERS,
            )
        } catch (error: IOException) {
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "NHentai mirror request failed",
                cause = error,
            )
        }
        if (response.statusCode !in 200..299) {
            throw SourceAdapterException(
                reason = mapHttpFailure(response.statusCode),
                message = "NHentai mirror request failed (${response.statusCode})",
            )
        }
        return response.body
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

    private fun parseJsonArray(body: String): JsonArray {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<")) {
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "NHentai returned non-JSON response (possibly blocked)",
            )
        }

        return runCatching { gson.fromJson(trimmed, JsonArray::class.java) }.getOrNull()
            ?: throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "NHentai returned malformed JSON",
            )
    }

    private fun parseGallery(raw: JsonObject): Post? {
        val galleryId = raw.get("id").asLongOrNull()?.toString()
            ?: raw.get("id").asStringOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val mediaId = raw.get("media_id").asLongOrNull()?.toString()
            ?: raw.get("media_id").asStringOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val oldImages = raw.optionalJsonObject("images")
        val oldThumbnail = oldImages?.optionalJsonObject("thumbnail")
        val oldCover = oldImages?.optionalJsonObject("cover")
        val oldPages = oldImages?.optionalJsonArray("pages").orEmpty()
        val newPages = raw.optionalJsonArray("pages").orEmpty()
        val pages = if (newPages.isNotEmpty()) newPages else oldPages

        val oldThumbExt = imageExtension(oldThumbnail?.get("t").asStringOrNull())
        val oldCoverExt = imageExtension(oldCover?.get("t").asStringOrNull())
        val newThumbnailPath = raw.get("thumbnail").asStringOrNull()
            ?: raw.optionalJsonObject("thumbnail")?.get("path").asStringOrNull()
        val newCoverPath = raw.get("cover").asStringOrNull()
            ?: raw.optionalJsonObject("cover")?.get("path").asStringOrNull()
        val previewUrl = sourceImageUrl(NHENTAI_THUMBS_BASE, newThumbnailPath)
            ?: "$NHENTAI_THUMBS_BASE/galleries/$mediaId/thumb.$oldThumbExt"
        val coverUrl = sourceImageUrl(NHENTAI_THUMBS_BASE, newCoverPath)
            ?: "$NHENTAI_THUMBS_BASE/galleries/$mediaId/cover.$oldCoverExt"

        val pageRefs = pages.sortedWithPageNumber().mapIndexedNotNull { index, pageElement ->
            val page = pageElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapIndexedNotNull null
            val path = page.get("path").asStringOrNull()
            val ext = imageExtension(path?.substringAfterLast('.') ?: page.get("t").asStringOrNull())
            val url = sourceImageUrl(NHENTAI_IMAGES_BASE, path)
                ?: "$NHENTAI_IMAGES_BASE/galleries/$mediaId/${index + 1}.$ext"
            ImageRef(
                url = url,
                localPath = null,
                mime = mimeFromFileExt(ext),
            )
        }

        val fallbackCoverRef = ImageRef(
            url = coverUrl,
            localPath = null,
            mime = mimeFromFileExt(newCoverPath?.substringAfterLast('.') ?: oldCoverExt),
        )

        val mirrorSparse = raw.get("mirror_sparse").asBooleanOrFalse()
        val mediaRefs = if (mirrorSparse) {
            emptyList()
        } else if (pageRefs.isNotEmpty()) {
            pageRefs
        } else if (raw.has("cover") || oldCover != null) {
            listOf(fallbackCoverRef)
        } else {
            emptyList()
        }

        val fullRef = mediaRefs.firstOrNull()
        val previewRef = ImageRef(
            url = previewUrl,
            localPath = null,
            mime = mimeFromFileExt(newThumbnailPath?.substringAfterLast('.') ?: oldThumbExt) ?: fullRef?.mime,
        )

        val tags = raw.optionalJsonArray("tags").orEmpty().mapNotNull { tagElement ->
            val tag = tagElement.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val name = tag.get("name").asStringOrNull()?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            ParsedNhentaiTag(
                name = name,
                type = tag.get("type").asStringOrNull()?.trim(),
            )
        }

        val canonicalTags = tags.map { it.name }.distinctBy { it.lowercase() }
        val rawTags = canonicalTags
        val titleObject = raw.optionalJsonObject("title")
        val title = listOf(
            titleObject?.get("pretty").asStringOrNull(),
            titleObject?.get("english").asStringOrNull(),
            titleObject?.get("japanese").asStringOrNull(),
            raw.get("english_title").asStringOrNull(),
            raw.get("japanese_title").asStringOrNull(),
        ).firstOrNull { !it.isNullOrBlank() }

        val scanlator = raw.get("scanlator").asStringOrNull()?.trim().orEmpty()
        val artist = tags.firstOrNull { it.type.equals("artist", ignoreCase = true) }?.name
        val authorName = scanlator.ifBlank { artist }
        val createdAtEpochMs = raw.get("upload_date").asLongOrNull()?.times(1000L)

        return Post(
            id = PostId(SourceKey.NHENTAI, galleryId),
            preview = previewRef,
            full = fullRef,
            media = mediaRefs,
            pageUrl = "https://nhentai.net/g/$galleryId/",
            width = raw.get("thumbnail_width").asIntOrNull()
                ?: oldThumbnail?.get("w").asIntOrNull()
                ?: oldCover?.get("w").asIntOrNull(),
            height = raw.get("thumbnail_height").asIntOrNull()
                ?: oldThumbnail?.get("h").asIntOrNull()
                ?: oldCover?.get("h").asIntOrNull(),
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
                val name = tag.get("name").asStringOrNull()?.trim().orEmpty()
                if (name.isBlank()) return@tagLoop
                if (normalizedPrefix.isNotBlank() && !name.lowercase().contains(normalizedPrefix)) {
                    return@tagLoop
                }

                val key = name.lowercase()
                val count = tag.get("count").asIntOrNull()
                val type = tag.get("type").asStringOrNull()?.trim()?.takeIf { it.isNotBlank() }
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

    private fun parseTagInfo(raw: JsonObject): NhentaiTagInfo? {
        val id = raw.get("id").asIntOrNull() ?: return null
        val name = raw.get("name").asStringOrNull()?.trim()?.takeIf(String::isNotBlank) ?: return null
        val type = raw.get("type").asStringOrNull()?.trim()?.takeIf(String::isNotBlank)
        val slug = raw.get("slug").asStringOrNull()?.trim()?.takeIf(String::isNotBlank)
            ?: name.toNhentaiSlug()
        val count = raw.get("count").asIntOrNull() ?: return null
        return NhentaiTagInfo(
            id = id,
            name = name,
            slug = slug,
            type = type,
            count = count,
        )
    }

    private fun tagSuggestion(info: NhentaiTagInfo): TagSuggestion {
        return TagSuggestion(
            text = info.name,
            type = info.type,
            count = info.count,
        )
    }

    private fun cacheTagInfo(info: NhentaiTagInfo) {
        tagInfoByKey[tagCacheKey(info.name)] = info
        tagInfoByKey[tagCacheKey(info.slug)] = info
    }

    private fun parseMirrorSearchPage(body: String): JsonObject {
        val posts = JsonArray()
        body.lineSequence()
            .mapNotNull(::parseMirrorSearchPost)
            .forEach(posts::add)
        val root = JsonObject()
        root.add("result", posts)
        root.addProperty("num_pages", parseMirrorPageCount(body))
        root.addProperty("per_page", posts.size().coerceAtLeast(1))
        return root
    }

    private fun parseMirrorSearchPost(line: String): JsonObject? {
        val galleryId = NHENTAI_MIRROR_GALLERY_LINK_REGEX.find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        val previewMatch = NHENTAI_MIRROR_THUMB_REGEX.find(line) ?: return null
        val previewUrl = previewMatch.groupValues[1]
        val mediaId = previewMatch.groupValues[2]
        val thumbExt = imageExtension(previewUrl.substringAfterLast('.').substringBefore('?'))
        val title = parseMirrorSearchTitle(line, previewUrl)

        return JsonObject().apply {
            addProperty("id", galleryId)
            addProperty("media_id", mediaId)
            add("title", JsonObject().apply {
                addProperty("pretty", title)
            })
            add("images", JsonObject().apply {
                add("thumbnail", JsonObject().apply {
                    addProperty("t", thumbExt)
                })
                add("cover", JsonObject().apply {
                    addProperty("t", thumbExt)
                })
                add("pages", JsonArray())
            })
            add("tags", JsonArray())
            addProperty("mirror_sparse", true)
        }
    }

    private fun parseMirrorSearchTitle(line: String, previewUrl: String): String? {
        val beforePreview = line.substringBefore("]($previewUrl)", missingDelimiterValue = "")
        val titleStart = beforePreview.indexOf(": ").takeIf { it >= 0 }?.plus(2) ?: return null
        val titleEnd = beforePreview.lastIndexOf("](").takeIf { it > titleStart } ?: beforePreview.length
        return beforePreview.substring(titleStart, titleEnd)
            .trim()
            .takeIf(String::isNotBlank)
    }

    private fun parseMirrorPageCount(body: String): Int {
        val linkedMaxPage = NHENTAI_MIRROR_PAGE_LINK_REGEX.findAll(body)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
        if (linkedMaxPage != null) return linkedMaxPage.coerceAtLeast(1)

        val totalResults = NHENTAI_MIRROR_RESULT_COUNT_REGEX.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", "")
            ?.toIntOrNull()
        return if (totalResults != null) {
            ((totalResults + NHENTAI_SEARCH_PAGE_SIZE - 1) / NHENTAI_SEARCH_PAGE_SIZE).coerceAtLeast(1)
        } else {
            1
        }
    }

    private fun parseMirrorTitle(body: String): String? {
        return body.lineSequence()
            .firstOrNull { line -> line.startsWith("Title: ") }
            ?.removePrefix("Title: ")
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun shouldUseMirrorFallback(response: SourceHttpResponse): Boolean {
        if (response.statusCode != 403) return false
        val challengeHeader = response.headers.entries.any { (name, values) ->
            name.equals("cf-mitigated", ignoreCase = true) &&
                values.any { value -> value.contains("challenge", ignoreCase = true) }
        }
        if (challengeHeader) return true
        val body = response.body.lowercase()
        return "cloudflare" in body || "cf-mitigated" in body || "attention required" in body
    }
}

private data class ParsedNhentaiTag(
    val name: String,
    val type: String?,
)

private data class NhentaiMirrorMetadata(
    val canonicalTags: List<String>,
    val authorName: String?,
)

private data class NhentaiTagInfo(
    val id: Int,
    val name: String,
    val slug: String,
    val type: String?,
    val count: Int,
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

private fun Query.directNhentaiGalleryIdCandidate(): String? {
    if (excludeTags.isNotEmpty()) return null
    val includes = includeTags
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
    val searchable = includes.filterNot { tag ->
        normalizeNhentaiFilterTag(tag) in NHENTAI_DIRECT_LOOKUP_FILTER_TAGS
    }
    if (searchable.size != 1) return null
    return searchable.first().takeIf(String::isDigitsOnly)
}

private fun Query.singleIncludeTagCandidate(): String? {
    if (excludeTags.isNotEmpty()) return null
    val includes = includeTags
        .asSequence()
        .map(::normalizeNhentaiTag)
        .filter(String::isNotBlank)
        .toList()
    if (includes.size != 1) return null
    return includes.first()
}

private fun normalizeNhentaiFilterTag(value: String): String {
    return value
        .trim()
        .lowercase()
        .replace('_', ' ')
        .replace(NHENTAI_WHITESPACE_REGEX, " ")
}

private fun String.isNhentaiTaxonomyPath(): Boolean {
    return startsWith("/tag/") ||
        startsWith("/artist/") ||
        startsWith("/character/") ||
        startsWith("/parody/") ||
        startsWith("/group/") ||
        startsWith("/language/") ||
        startsWith("/category/")
}

private fun mapSortParam(sortMode: SortMode): String? {
    return when (sortMode) {
        SortMode.NEWEST -> null
        SortMode.POPULAR -> "popular-today"
        SortMode.TOP -> "popular-week"
        SortMode.RANDOM -> null
    }
}

private fun mapTaggedSortParam(sortMode: SortMode): String? {
    return when (sortMode) {
        SortMode.NEWEST -> "date"
        SortMode.POPULAR -> "popular-today"
        SortMode.TOP -> "popular-week"
        SortMode.RANDOM -> null
    }
}

private fun mapMirrorSortParam(sortMode: SortMode): String? {
    return when (sortMode) {
        SortMode.NEWEST -> "date"
        SortMode.POPULAR -> "popular-today"
        SortMode.TOP -> "popular-week"
        SortMode.RANDOM -> "date"
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
        "w", "webp" -> "webp"
        "jpeg" -> "jpg"
        else -> "jpg"
    }
}

private fun imageUrlCandidates(baseUrl: String, page: Int, primaryExt: String): List<String> {
    return (listOf(primaryExt) + NHENTAI_IMAGE_FALLBACK_EXTENSIONS)
        .distinct()
        .map { ext -> "$baseUrl/$page.$ext" }
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

private fun List<JsonElement>.sortedWithPageNumber(): List<JsonElement> {
    return sortedWith(compareBy { element ->
        element.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?.get("number")
            .asIntOrNull()
            ?: Int.MAX_VALUE
    })
}

private fun sourceImageUrl(baseUrl: String, path: String?): String? {
    val normalizedPath = path
        ?.trim()
        ?.trimStart('/')
        ?.takeIf(String::isNotBlank)
        ?: return null
    if (normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://")) {
        return normalizedPath
    }
    return "${baseUrl.trimEnd('/')}/$normalizedPath"
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

private fun JsonElement?.asStringOrNull(): String? {
    if (this == null || this.isJsonNull) return null
    if (!this.isJsonPrimitive) return null
    val primitive = this.asJsonPrimitive
    if (!primitive.isString && !primitive.isNumber && !primitive.isBoolean) return null
    return runCatching { primitive.asString }.getOrNull()
}

private fun JsonElement?.asBooleanOrFalse(): Boolean {
    if (this == null || this.isJsonNull) return false
    val primitive = this.asJsonPrimitive
    if (!primitive.isBoolean && !primitive.isString) return false
    return runCatching { primitive.asBoolean }.getOrDefault(false)
}

private fun String.isDigitsOnly(): Boolean {
    return isNotBlank() && all { ch -> ch.isDigit() }
}

private fun String.toNhentaiSlug(): String {
    return normalizeNhentaiTag(this)
        .lowercase()
        .replace(NHENTAI_WHITESPACE_REGEX, "-")
}

private fun tagCacheKey(value: String): String {
    return normalizeNhentaiTag(value).lowercase()
}

private fun String.urlEncode(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
}

private const val NHENTAI_WEB_BASE = "nhentai.net/"
private const val NHENTAI_GALLERIES_ALL_URL = "https://nhentai.net/api/v2/galleries"
private const val NHENTAI_GALLERIES_TAGGED_URL = "https://nhentai.net/api/v2/galleries/tagged"
private const val NHENTAI_GALLERIES_SEARCH_URL = "https://nhentai.net/api/v2/search"
private const val NHENTAI_GALLERY_URL_PREFIX = "https://nhentai.net/api/v2/galleries"
private const val NHENTAI_TAGS_SEARCH_URL = "https://nhentai.net/api/v2/tags/search"
private const val NHENTAI_IMAGES_BASE = "https://i.nhentai.net"
private const val NHENTAI_THUMBS_BASE = "https://t.nhentai.net"
private const val JINA_MIRROR_BASE = "https://r.jina.ai/http://"
private val NHENTAI_METADATA_MIRROR_BASES = listOf(
    "https://nhentai.to",
    "https://nhentai.website",
)
private const val NHENTAI_SEARCH_PAGE_SIZE = 25
private const val NHENTAI_V2_SEARCH_ALL_QUERY = "*"
private val NHENTAI_DEFAULT_HEADERS = mapOf(
    "User-Agent" to "TheoriaCodex/1.0 (Android source adapter)",
    "Referer" to "https://nhentai.net/",
    "Accept" to "application/json, text/plain, */*",
)
private val NHENTAI_JSON_POST_HEADERS = NHENTAI_DEFAULT_HEADERS + (
    "Content-Type" to "application/json"
)
private val JINA_REQUEST_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0",
    "Accept" to "text/plain, */*",
)
private val NHENTAI_METADATA_MIRROR_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
)
private val NHENTAI_IMAGE_FALLBACK_EXTENSIONS = listOf("webp", "jpg", "png", "gif")
private const val NHENTAI_FULL_COLOR_TAG = "full color"
private val NHENTAI_LANGUAGE_FILTER_TAGS = setOf("english", "chinese", "japanese")
private val NHENTAI_DIRECT_LOOKUP_FILTER_TAGS = NHENTAI_LANGUAGE_FILTER_TAGS + NHENTAI_FULL_COLOR_TAG
private val NHENTAI_WHITESPACE_REGEX = Regex("\\s+")
private val NHENTAI_MIRROR_THUMB_REGEX =
    Regex("""(https://t\d*\.nhentai\.net/galleries/(\d+)/thumb[^\)]*)""")
private val NHENTAI_MIRROR_GALLERY_LINK_REGEX = Regex("""https?://nhentai\.net/g/(\d+)/""")
private val NHENTAI_MIRROR_PAGE_LINK_REGEX = Regex("""[?&]page=(\d+)""")
private val NHENTAI_MIRROR_RESULT_COUNT_REGEX = Regex("""(?m)^#+\s*([\d,]+)\s+results\b""")
private val NHENTAI_MIRROR_PAGE_IMAGE_REGEX =
    Regex("""https://i\d*\.nhentai\.net/galleries/(\d+)/1\.(webp|jpg|jpeg|png|gif)""")
private val NHENTAI_MIRROR_PAGE_COUNT_REGEX = Regex("""\b1\s+of\s+(\d+)\b""")
