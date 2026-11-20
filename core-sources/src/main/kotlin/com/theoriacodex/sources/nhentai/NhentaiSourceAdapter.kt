package com.theoriacodex.sources.nhentai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.CommentsSourceAdapter
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.RelatedPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.common.BoundedLruCache
import com.theoriacodex.sources.common.asIntOrNull
import com.theoriacodex.sources.common.asStringOrNull
import com.theoriacodex.sources.common.canonicalPostPage
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.common.isSuccessful
import com.theoriacodex.sources.common.matchesChallenge
import com.theoriacodex.sources.common.optionalJsonArray
import com.theoriacodex.sources.common.parseJsonArray as parseSourceJsonArray
import com.theoriacodex.sources.common.parseJsonObject as parseSourceJsonObject
import com.theoriacodex.sources.common.sourceCall
import com.theoriacodex.sources.common.sourceNetworkFailure
import com.theoriacodex.sources.http.RequestPacer
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.http.THEORIA_SOURCE_USER_AGENT
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import com.theoriacodex.sources.common.objects

class NhentaiSourceAdapter(
    private val httpClient: SourceHttpClient,
    private val gson: Gson = Gson(),
    private val minRequestIntervalMs: Long = 350L,
    /** Where entry points run; tests pass EmptyCoroutineContext to keep virtual time. Production never sets it. */
    private val callContext: CoroutineContext = Dispatchers.Default,
) : SourceAdapter, TagCountLookupSourceAdapter, FacetedSearchSourceAdapter, RelatedPostsSourceAdapter, CreatorPostsSourceAdapter,
    CommentsSourceAdapter {
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

    override val supportedSearchScopes: Set<FacetedSearchScope> = NHENTAI_FACETS.searchScopes

    private val pacer = RequestPacer(minRequestIntervalMs)
    private val tagInfoByKey = BoundedLruCache<String, List<NhentaiTagInfo>>(TAG_CACHE_MAX_KEYS)
    private val mirror = NhentaiMirror(httpClient, pacer)

    override suspend fun search(query: Query, pageToken: String?): Page<Post> = sourceCall(callContext) {
        val directGalleryId = query.directNhentaiGalleryIdCandidate()
        if (directGalleryId != null) {
            val resolved = resolvePost(
                PostId(
                    source = SourceKey.NHENTAI,
                    sourcePostId = directGalleryId,
                )
            )
            return@sourceCall canonicalPostPage(
                items = listOfNotNull(resolved),
                nextPageToken = null,
            )
        }

        val pageIndex = pageToken?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val compiledQuery = compileNhentaiQuery(query)
        val params = linkedMapOf("page" to pageIndex.toString())
        val exactTag = exactTagFor(query)
        val useAllEndpoint = compiledQuery.isBlank() &&
            (query.sort == SortMode.NEWEST || query.sort == SortMode.RANDOM)

        val url = searchUrl(query.sort, exactTag, useAllEndpoint, compiledQuery, params)

        val response = requestDirect(url = url, query = params)
        val page = if (shouldUseMirrorFallback(response)) {
            mirror.search(compiledQuery, query.sort, pageIndex, useAllEndpoint)
        } else {
            apiSearchPage(response)
        }
        val normalizedPosts = if (query.sort == SortMode.RANDOM) page.posts.shuffled() else page.posts

        val totalPages = page.totalPages
        val nextPageToken = if (totalPages != null && pageIndex < totalPages) {
            (pageIndex + 1).toString()
        } else {
            null
        }

        canonicalPostPage(
            items = normalizedPosts,
            nextPageToken = nextPageToken,
        )
    }

    /** A single include tag nhentai knows, for its tag endpoint; random sort and failed lookups use plain search. */
    private suspend fun exactTagFor(query: Query): NhentaiTagInfo? {
        if (query.sort == SortMode.RANDOM) return null
        val tag = query.singleIncludeTagCandidate() ?: return null
        return runCatchingPreservingCancellation { resolveNhentaiTag(tag) }.getOrNull()
    }

    private fun apiSearchPage(response: SourceHttpResponse): NhentaiSearchPage {
        val root = parseJsonObject(response.ensureNhentaiOk())
        return NhentaiSearchPage(
            posts = root.optionalJsonArray("result").galleryPosts(),
            totalPages = root.get("num_pages").asIntOrNull(),
        )
    }

    override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> = sourceCall(callContext) {
        if (creator.source != sourceKey) return@sourceCall Page(emptyList(), null)
        val artist = creator.profileId?.takeIf(String::isNotBlank) ?: return@sourceCall Page(emptyList(), null)
        search(
            query = Query(
                mode = QueryMode.Source(sourceKey),
                includeTerms = listOf(SearchTerm(artist, SearchFacet.ARTIST, NHENTAI_ARTIST_NAMESPACE)),
                excludeTerms = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ),
            pageToken = pageToken,
        )
    }

    private fun searchUrl(
        sort: SortMode,
        exactTag: NhentaiTagInfo?,
        useAllEndpoint: Boolean,
        compiledQuery: String,
        params: MutableMap<String, String>,
    ): String {
        return if (exactTag != null) {
            params["tag_id"] = exactTag.id.toString()
            mapTaggedSortParam(sort)?.let { sort ->
                params["sort"] = sort
            }
            NHENTAI_GALLERIES_TAGGED_URL
        } else if (useAllEndpoint) {
            NHENTAI_GALLERIES_URL
        } else {
            params["query"] = compiledQuery.ifBlank { NHENTAI_V2_SEARCH_ALL_QUERY }
            mapSortParam(sort)?.let { sort ->
                params["sort"] = sort
            }
            NHENTAI_GALLERIES_SEARCH_URL
        }
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = sourceCall(callContext) {
        if (limit <= 0) return@sourceCall emptyList()
        val root = requestJsonObject(
            url = NHENTAI_GALLERIES_URL,
            query = mapOf("page" to "1"),
        )
        val galleryIds = root.objects("result")
            .asSequence()
            .mapNotNull { gallery -> gallery.get("id").asIntOrNull() }
            .distinct()
            .take(NHENTAI_TRENDING_GALLERY_SAMPLE_SIZE)
            .toList()
        val detailedGalleries = galleryIds.mapConcurrent { galleryId ->
            // A gallery removed since the listing loaded is skipped.
            val response = requestDirect(url = "$NHENTAI_GALLERIES_URL/$galleryId", query = emptyMap())
            if (response.statusCode == 404) null else parseJsonObject(response.ensureNhentaiOk())
        }
        collectNhentaiTagSuggestions(
            galleries = detailedGalleries.filterNotNull(),
            prefix = null,
            limit = limit,
        )
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = sourceCall(callContext) {
        val normalizedPrefix = prefix.trim()
        if (normalizedPrefix.isBlank() || limit <= 0) return@sourceCall emptyList()

        requestTagSearch(normalizedPrefix)
            .map(::tagSuggestion)
            .take(limit)
    }

    override suspend fun autocompleteFaceted(
        prefix: String,
        scope: FacetedSearchScope,
        limit: Int,
    ): List<FacetedTagSuggestion> = sourceCall(callContext) {
        val normalizedPrefix = prefix.trim()
        if (normalizedPrefix.isBlank() || limit <= 0 || scope !in supportedSearchScopes) {
            return@sourceCall emptyList()
        }

        requestTagSearch(normalizedPrefix)
            .asSequence()
            .map(NhentaiTagInfo::toFacetedSuggestion)
            .filter { suggestion ->
                scope.isAll || (
                    suggestion.facet == scope.facet &&
                        suggestion.sourceNamespace == scope.sourceNamespace
                    )
            }
            .take(limit)
            .toList()
    }

    override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> = sourceCall(callContext) {
        val counts = linkedMapOf<String, Int>()
        fetchTagCountsIncrementally(tags) { counts.putAll(it) }
        counts
    }

    override suspend fun fetchTagCountsIncrementally(tags: List<String>, onCounts: (Map<String, Int>) -> Unit): Unit = sourceCall(callContext) {
        tags.asSequence()
            .map(::normalizeNhentaiTag)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { tag ->
                resolveNhentaiTag(tag)?.let { info -> onCounts(mapOf(info.name to info.count)) }
            }
    }

    override suspend fun quickQuery(kind: QuickQueryKind): Query = sourceCall(callContext) {
        val sort = when (kind) {
            QuickQueryKind.POPULAR_TODAY -> SortMode.POPULAR
            QuickQueryKind.TOP_7D -> SortMode.TOP
            QuickQueryKind.TOP_30D -> SortMode.TOP
            QuickQueryKind.NEWEST -> SortMode.NEWEST
            QuickQueryKind.RANDOM -> SortMode.RANDOM
        }
        Query(
            mode = QueryMode.Source(SourceKey.NHENTAI),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = sort,
            dateRange = null,
            minScore = null,
        )
    }

    override suspend fun resolvePost(id: PostId): Post? = sourceCall(callContext) {
        if (id.source != SourceKey.NHENTAI) return@sourceCall null
        val galleryId = id.sourcePostId.trim().takeIf(String::isDigitsOnly) ?: return@sourceCall null

        val response = requestDirect(
            url = "$NHENTAI_GALLERIES_URL/$galleryId",
            query = emptyMap(),
        )
        if (response.statusCode == 404) return@sourceCall null
        if (shouldUseMirrorFallback(response)) {
            return@sourceCall mirror.resolvePost(galleryId)
        }
        parseNhentaiGallery(parseJsonObject(response.ensureNhentaiOk()))
    }

    /** Uses the site's More like this recommendations without substituting a tag search. */
    override suspend fun relatedPosts(seed: PostId, limit: Int): List<Post> = sourceCall(callContext) {
        if (seed.source != SourceKey.NHENTAI || limit <= 0) return@sourceCall emptyList()
        val galleryId = seed.sourcePostId.trim().takeIf(String::isDigitsOnly) ?: return@sourceCall emptyList()
        val root = requestJsonObject(url = "$NHENTAI_GALLERIES_URL/$galleryId/related")
        val galleries = root.optionalJsonArray("result") ?: throw SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = "NHentai related galleries were not found in the response",
        )
        galleries.galleryPosts()
            .asSequence()
            .filterNot { post -> post.id.sourcePostId == galleryId }
            .distinctBy(Post::id)
            .take(limit.coerceAtMost(NHENTAI_MAX_RELATED_POSTS))
            .toList()
    }

    /**
     * Comments exist only on the direct API; the mirrors do not carry them, so a blocked request
     * fails instead of falling back. Replies do not exist on nhentai, so [parent] is ignored.
     */
    override suspend fun comments(post: Post, pageToken: String?, parent: PostComment?): CommentPage = sourceCall(callContext) {
        val galleryId = post.id.sourcePostId.trim().takeIf { post.id.source == sourceKey && it.isDigitsOnly() }
            ?: return@sourceCall CommentPage(emptyList(), total = 0, nextPageToken = null)
        val pageIndex = pageToken?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val root = requestJsonObject(
            url = "$NHENTAI_GALLERIES_URL/$galleryId/comments",
            query = mapOf("page" to pageIndex.toString(), "per_page" to NHENTAI_COMMENTS_PER_PAGE.toString()),
        )
        parseNhentaiCommentPage(root, pageIndex)
    }

    private suspend fun requestJsonObject(
        url: String,
        query: Map<String, String> = emptyMap(),
    ): JsonObject = parseJsonObject(requestDirect(url = url, query = query).ensureNhentaiOk())

    private suspend fun requestJsonArrayPost(
        url: String,
        body: String,
    ): JsonArray = parseSourceJsonArray(jsonBody(requestJsonPost(url = url, body = body).ensureNhentaiOk()), gson, "NHentai")

    private suspend fun requestDirect(
        url: String,
        query: Map<String, String>,
    ): SourceHttpResponse {
        pacer.awaitTurn()
        return try {
            httpClient.get(
                url = url,
                query = query,
                headers = NHENTAI_DEFAULT_HEADERS,
            )
        } catch (error: IOException) {
            sourceNetworkFailure("NHentai", error)
        }
    }

    private suspend fun requestJsonPost(
        url: String,
        body: String,
    ): SourceHttpResponse {
        pacer.awaitTurn()
        return try {
            httpClient.postJson(
                url = url,
                body = body,
                headers = NHENTAI_JSON_POST_HEADERS,
            )
        } catch (error: IOException) {
            sourceNetworkFailure("NHentai", error)
        }
    }

    private suspend fun requestTagSearch(query: String): List<NhentaiTagInfo> {
        val normalizedQuery = normalizeNhentaiTag(query)
        if (normalizedQuery.isBlank()) return emptyList()

        val body = gson.toJson(
            mapOf(
                "query" to normalizedQuery,
            )
        )
        return requestJsonArrayPost(
            url = NHENTAI_TAGS_SEARCH_URL,
            body = body,
        ).objects().mapNotNull(::parseTagInfo).also { tags ->
            tags.forEach(::cacheTagInfo)
        }
    }

    private suspend fun resolveNhentaiTag(tag: String): NhentaiTagInfo? {
        val normalized = normalizeNhentaiTag(tag)
        if (normalized.isBlank()) return null
        tagInfoByKey[tagCacheKey(normalized)]
            ?.firstOrNull(NhentaiTagInfo::isGeneralTag)
            ?.let { return it }
        val normalizedSlug = normalized.toNhentaiSlug()
        return requestTagSearch(normalized)
            .firstOrNull { info ->
                info.isGeneralTag() && (
                    info.name.equals(normalized, ignoreCase = true) ||
                        info.slug.equals(normalizedSlug, ignoreCase = true)
                    )
            }
    }

    private fun parseJsonObject(response: SourceHttpResponse): JsonObject =
        parseSourceJsonObject(jsonBody(response), gson, "NHentai")

    /** The trimmed body, refusing an HTML page: nhentai answers a blocked request with one. */
    private fun jsonBody(response: SourceHttpResponse): String {
        val trimmed = response.body.trimStart()
        if (trimmed.startsWith("<")) {
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "NHentai returned non-JSON response (possibly blocked)",
            )
        }
        return trimmed
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
        listOf(info.name, info.slug)
            .map(::tagCacheKey)
            .distinct()
            .forEach { key ->
                tagInfoByKey.update(key) { bucket ->
                    val cached = bucket.orEmpty()
                    if (cached.any { it.id == info.id }) {
                        cached.map { if (it.id == info.id) info else it }
                    } else {
                        cached + info
                    }
                }
            }
    }

    private fun shouldUseMirrorFallback(response: SourceHttpResponse): Boolean {
        return response.matchesChallenge(
            bodyMarkers = setOf("cloudflare", "cf-mitigated", "attention required"),
        )
    }
}

/** Each cached tag fills two keys (name and slug), so this keeps about a thousand tags. */
private const val TAG_CACHE_MAX_KEYS = 2_000

private data class NhentaiTagInfo(
    val id: Int,
    val name: String,
    val slug: String,
    val type: String?,
    val count: Int,
)

private fun NhentaiTagInfo.isGeneralTag(): Boolean {
    return type == null || type.equals(NHENTAI_TAG_NAMESPACE, ignoreCase = true)
}

private fun NhentaiTagInfo.toFacetedSuggestion(): FacetedTagSuggestion {
    val taxonomy = nhentaiTaxonomy(type)
    return FacetedTagSuggestion(
        text = name,
        facet = taxonomy.first,
        sourceNamespace = taxonomy.second,
        count = count,
    )
}

internal fun nhentaiTaxonomy(type: String?): Pair<SearchFacet, String> {
    val namespace = type?.trim()?.lowercase()
    val facet = namespace?.let(NHENTAI_FACETS::facetOf) ?: return SearchFacet.TAG to NHENTAI_TAG_NAMESPACE
    return facet to namespace
}

internal fun String.isNhentaiTaxonomyPath(): Boolean {
    return NHENTAI_FACETS.namespaces.any { namespace -> startsWith("/$namespace/") }
}

internal fun String.nhentaiTaxonomyNamespace(): String? {
    return trim()
        .removePrefix("/")
        .substringBefore('/')
        .takeIf(String::isNotBlank)
}

/** This response, or the failure nhentai's status means: 401 and 403 are not credential problems here. */
internal fun SourceHttpResponse.ensureNhentaiOk(label: String = "NHentai"): SourceHttpResponse {
    if (isSuccessful()) return this
    throw SourceAdapterException(
        reason = classifyHttpFailure(
            statusCode = statusCode,
            unauthorizedReason = SourceFailureReason.UNKNOWN,
            forbiddenReason = SourceFailureReason.UNKNOWN,
        ),
        message = "$label request failed ($statusCode)",
    )
}

/** API gallery records in [this] array, skipping entries that are not galleries. */
private fun JsonArray?.galleryPosts(): List<Post> = objects().mapNotNull(::parseNhentaiGallery)

internal fun imageExtension(type: String?): String {
    return when (type?.trim()?.lowercase()) {
        "p", "png" -> "png"
        "g", "gif" -> "gif"
        "w", "webp" -> "webp"
        "jpeg" -> "jpg"
        else -> "jpg"
    }
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

private const val NHENTAI_GALLERIES_URL = "https://nhentai.net/api/v2/galleries"
private const val NHENTAI_GALLERIES_TAGGED_URL = "https://nhentai.net/api/v2/galleries/tagged"
private const val NHENTAI_GALLERIES_SEARCH_URL = "https://nhentai.net/api/v2/search"
private const val NHENTAI_TAGS_SEARCH_URL = "https://nhentai.net/api/v2/tags/search"
internal const val NHENTAI_IMAGES_BASE = "https://i.nhentai.net"
internal const val NHENTAI_THUMBS_BASE = "https://t.nhentai.net"
private const val NHENTAI_TRENDING_GALLERY_SAMPLE_SIZE = 6
private const val NHENTAI_MAX_RELATED_POSTS = 6
internal const val NHENTAI_SEARCH_PAGE_SIZE = 25
private const val NHENTAI_V2_SEARCH_ALL_QUERY = "*"
private val NHENTAI_DEFAULT_HEADERS = mapOf(
    "User-Agent" to THEORIA_SOURCE_USER_AGENT,
    "Referer" to "https://nhentai.net/",
    "Accept" to "application/json, text/plain, */*",
)
private val NHENTAI_JSON_POST_HEADERS = NHENTAI_DEFAULT_HEADERS + (
    "Content-Type" to "application/json"
)
