package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.MediaRecoverySourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.sources.common.asIntOrNull
import com.theoriacodex.sources.common.autocompleteTagCounts
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.canonicalHitomiArtistIdentity
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.common.sourceCall
import com.theoriacodex.sources.http.SourceHttpBodyTooLargeException
import com.theoriacodex.sources.http.SourceHttpClient
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * Hitomi's search pages are metadata shells. This adapter reads the provider's binary Nozomi
 * indexes directly, performs exact typed intersections/subtractions, and hydrates only the IDs
 * needed for the current page.
 */
class HitomiSourceAdapter(
    private val httpClient: SourceHttpClient,
    private val mediaUrlResolver: HitomiMediaUrlResolver = HitomiMediaUrlResolver(httpClient),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val hydrationConcurrency: Int = DEFAULT_HYDRATION_CONCURRENCY,
    globalIndexCacheMaxBytes: Long = HitomiGlobalIndexCache.DEFAULT_MAX_BYTES,
    membershipCacheMaxBytes: Long = DEFAULT_MEMBERSHIP_CACHE_BYTES,
    randomSnapshotCacheMaxBytes: Long = HitomiRandomSnapshotCache.DEFAULT_MAX_BYTES,
    randomSnapshotReuseTtlMillis: Long = HitomiRandomSnapshotCache.DEFAULT_INITIAL_REUSE_TTL_MILLIS,
    randomSnapshotNowMillis: () -> Long = System::currentTimeMillis,
    knownSizeCacheMaxBytes: Long = DEFAULT_KNOWN_SIZE_CACHE_BYTES,
    suggestionCountCacheMaxBytes: Long = DEFAULT_SUGGESTION_COUNT_CACHE_BYTES,
    galleryManifestCacheMaxBytes: Long = DEFAULT_GALLERY_MANIFEST_CACHE_BYTES,
    galleryManifestTtlMillis: Long = DEFAULT_GALLERY_MANIFEST_TTL_MILLIS,
    galleryManifestNowMillis: () -> Long = System::currentTimeMillis,
    private val mediaCandidates: suspend (HitomiMediaFile) -> List<HitomiMediaCandidate> = { file ->
        mediaUrlResolver.candidates(file)
    },
    /** Where entry points run; tests pass EmptyCoroutineContext to keep virtual time. Production never sets it. */
    private val callContext: CoroutineContext = Dispatchers.Default,
) : SourceAdapter, FacetedSearchSourceAdapter, CreatorPostsSourceAdapter, MediaRecoverySourceAdapter, TagCountLookupSourceAdapter {
    override val sourceKey: SourceKey = SourceKey.HITOMI

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

    override val supportedSearchScopes: Set<FacetedSearchScope> = HITOMI_FACETS.searchScopes

    private val postMapper = HitomiPostMapper(mediaUrlResolver, mediaCandidates)

    override suspend fun recoverPostMedia(post: Post, failedMedia: ImageRef): Post? = sourceCall(callContext) {
        hitomiCall("media recovery") { postMapper.recoverPostMedia(post, failedMedia) }
    }

    private val suggestionCounts = HitomiByteBudgetCache<String, Int>(
        maxBytes = suggestionCountCacheMaxBytes,
        weigh = { key, _ -> key.hitomiUtf8ByteWeight() + Int.SIZE_BYTES },
    )
    private val globalIndexCache = HitomiGlobalIndexCache(globalIndexCacheMaxBytes)
    private val queryCompiler = HitomiQueryCompiler(httpClient, globalIndexCache)
    private val nozomiReader = HitomiNozomiReader(httpClient, knownSizeCacheMaxBytes, membershipCacheMaxBytes)
    private val randomSnapshotCache = HitomiRandomSnapshotCache(
        maxBytes = randomSnapshotCacheMaxBytes,
        initialReuseTtlMillis = randomSnapshotReuseTtlMillis,
        nowMillis = randomSnapshotNowMillis,
    )
    private val searchPager = HitomiSearchPager(
        queryCompiler = queryCompiler,
        nozomiReader = nozomiReader,
        randomSnapshotCache = randomSnapshotCache,
        suggestionCounts = suggestionCounts,
        pageSize = pageSize,
    )
    private val galleryManifests = HitomiGalleryManifestStore(
        maxBytes = galleryManifestCacheMaxBytes,
        ttlMillis = galleryManifestTtlMillis,
        nowMillis = galleryManifestNowMillis,
    )

    init {
        require(pageSize in 1..MAX_PAGE_SIZE) {
            "Hitomi page size must be within 1..$MAX_PAGE_SIZE"
        }
        require(hydrationConcurrency in 1..MAX_HYDRATION_CONCURRENCY) {
            "Hitomi hydration concurrency must be within 1..$MAX_HYDRATION_CONCURRENCY"
        }
    }

    override suspend fun search(query: Query, pageToken: String?): Page<Post> = sourceCall(callContext) {
        hitomiCall("search") { searchInternal(query, pageToken) }
    }

    private suspend fun searchInternal(query: Query, pageToken: String?): Page<Post> {
        val page = searchPager.page(query, pageToken)
        return Page(items = hydrateGalleries(page.ids), nextPageToken = page.nextToken)
    }

    internal suspend fun cacheSnapshot(): HitomiSourceCacheSnapshot {
        return HitomiSourceCacheSnapshot(
            globalIndex = globalIndexCache.snapshot(),
            membership = nozomiReader.membershipSnapshot(),
            random = randomSnapshotCache.snapshot(),
            knownSizes = nozomiReader.knownSizeSnapshot(),
            suggestionCounts = suggestionCounts.snapshot(),
        )
    }

    override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> = sourceCall(callContext) {
        autocompleteTagCounts(sourceKey, tags, ::autocompleteTags)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = sourceCall(callContext) {
        emptyList()
    }

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = sourceCall(callContext) {
        autocompleteFaceted(
            prefix = prefix,
            scope = FacetedSearchScope.All,
            limit = limit,
        ).map { suggestion ->
            TagSuggestion(
                text = suggestion.text,
                type = suggestion.sourceNamespace,
                count = suggestion.count,
            )
        }
    }

    override suspend fun autocompleteFaceted(
        prefix: String,
        scope: FacetedSearchScope,
        limit: Int,
    ): List<FacetedTagSuggestion> = sourceCall(callContext) {
        hitomiCall("autocomplete") { autocompleteFacetedInternal(prefix, scope, limit) }
    }

    override suspend fun featuredFacetedSuggestions(
        scope: FacetedSearchScope,
        limit: Int,
    ): List<FacetedTagSuggestion> = sourceCall(callContext) {
        if (limit <= 0 || scope !in supportedSearchScopes) return@sourceCall emptyList()
        val values = when (scope.facet) {
            SearchFacet.TYPE -> HITOMI_FEATURED_TYPES
            SearchFacet.LANGUAGE -> HITOMI_FEATURED_LANGUAGES
            else -> emptyList()
        }
        values.take(limit).map { value ->
            FacetedTagSuggestion(
                text = value,
                facet = requireNotNull(scope.facet),
                sourceNamespace = scope.sourceNamespace,
                count = suggestionCounts.get(
                    SearchTerm(
                        value = value,
                        facet = requireNotNull(scope.facet),
                        sourceNamespace = scope.sourceNamespace,
                    ).termCacheKey(),
                ),
            )
        }
    }

    private suspend fun autocompleteFacetedInternal(
        prefix: String,
        scope: FacetedSearchScope,
        limit: Int,
    ): List<FacetedTagSuggestion> {
        val normalizedPrefix = prefix.trim()
        if (normalizedPrefix.isBlank() || limit <= 0 || scope !in supportedSearchScopes) {
            return emptyList()
        }
        val providerScope = scope.toHitomiAutocompleteScope()
        val url = HitomiProtocol.autocompleteUrl(providerScope, normalizedPrefix)
        val response = requestText(url)
        if (response.statusCode !in 200..299) {
            throw hitomiHttpFailure("autocomplete", response.statusCode)
        }
        return HitomiProtocol.parseAutocomplete(
            body = response.body,
            maxResults = limit.coerceAtMost(HITOMI_AUTOCOMPLETE_LIMIT),
        ).map { entry -> entry.toFacetedSuggestion() }
            .also { suggestions ->
                suggestions.forEach { suggestion ->
                    suggestion.count?.let { count ->
                        suggestionCounts.put(suggestion.termCacheKey(), count)
                    }
                }
            }
    }

    override suspend fun quickQuery(kind: QuickQueryKind): Query = sourceCall(callContext) {
        val sort = when (kind) {
            QuickQueryKind.POPULAR_TODAY -> SortMode.POPULAR
            QuickQueryKind.TOP_7D, QuickQueryKind.TOP_30D -> SortMode.TOP
            QuickQueryKind.NEWEST -> SortMode.NEWEST
            QuickQueryKind.RANDOM -> SortMode.RANDOM
        }
        Query(
            mode = QueryMode.Source(SourceKey.HITOMI),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = sort,
            dateRange = null,
            minScore = null,
        )
    }

    override suspend fun resolvePost(id: PostId): Post? = sourceCall(callContext) {
        if (id.source != SourceKey.HITOMI) return@sourceCall null
        val galleryId = id.sourcePostId.trim().toIntOrNull()?.takeIf { value -> value > 0 }
            ?: return@sourceCall null
        hitomiCall("post") { fetchGallery(galleryId, sparse = false) }
    }

    override suspend fun searchCreatorPosts(
        creator: CreatorProfile,
        pageToken: String?,
    ): Page<Post> = sourceCall(callContext) {
        val artist = creator.canonicalHitomiArtistIdentity()
            ?: return@sourceCall Page(items = emptyList(), nextPageToken = null)
        search(
            query = Query(
                mode = QueryMode.Source(SourceKey.HITOMI),
                includeTerms = listOf(
                    SearchTerm(
                        value = artist,
                        facet = SearchFacet.ARTIST,
                        sourceNamespace = HITOMI_ARTIST_NAMESPACE,
                    ),
                ),
                excludeTerms = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ),
            pageToken = pageToken,
        )
    }

    private suspend fun hydrateGalleries(ids: IntArray): List<Post> =
        ids.asList().mapConcurrent(hydrationConcurrency) { galleryId ->
            try {
                fetchGallery(galleryId, sparse = true)
            } catch (_: HitomiGalleryException) {
                null
            }
        }.filterNotNull().distinctBy(Post::id)

    private suspend fun fetchGallery(galleryId: Int, sparse: Boolean): Post? {
        val gallery = galleryManifests.getOrLoad(galleryId) { loadGalleryManifest(galleryId) }
            ?: return null
        return postMapper.toPost(parseHitomiGallery(gallery, galleryId), sparse)
    }

    private suspend fun loadGalleryManifest(galleryId: Int): HitomiGalleryManifest? {
        val url = HitomiProtocol.galleryUrl(galleryId)
        val response = try {
            requestText(url)
        } catch (error: SourceAdapterException) {
            throw HitomiGalleryException(
                reason = error.reason,
                message = "Hitomi gallery $galleryId request failed",
                cause = error,
            )
        }
        if (response.statusCode == 404 || response.statusCode == 410) return null
        if (response.statusCode !in 200..299) {
            throw HitomiGalleryException(
                reason = classifyHttpFailure(response.statusCode),
                message = "Hitomi gallery $galleryId request failed (${response.statusCode})",
            )
        }
        val gallery = try {
            HitomiProtocol.parseGalleryAssignment(response.body)
        } catch (error: HitomiProtocolException) {
            throw HitomiGalleryException(
                reason = SourceFailureReason.PARSE,
                message = "Hitomi gallery $galleryId was malformed",
                cause = error,
            )
        }
        if (gallery["blocked"].asIntOrNull() == 1) return null
        return HitomiGalleryManifest(
            gallery = gallery,
            payloadBytes = response.body.hitomiUtf8ByteWeight(),
        )
    }

    private suspend fun requestText(url: String) = try {
        httpClient.get(url = url, headers = HitomiProtocol.requestHeaders)
    } catch (error: SourceHttpBodyTooLargeException) {
        throw hitomiParseFailure("Hitomi response exceeded its bounded size ($url)", error)
    } catch (error: IOException) {
        throw SourceAdapterException(
            reason = SourceFailureReason.NETWORK,
            message = "Hitomi request failed ($url)",
            cause = error,
        )
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 25
        private const val MAX_PAGE_SIZE = 100

        /** One per card: a page multiplexes over a single HTTP/2 connection, as Hitomi's own gallery list does. */
        private const val DEFAULT_HYDRATION_CONCURRENCY = DEFAULT_PAGE_SIZE
        private const val MAX_HYDRATION_CONCURRENCY = MAX_PAGE_SIZE
        private const val HITOMI_AUTOCOMPLETE_LIMIT = 10
        internal const val DEFAULT_MEMBERSHIP_CACHE_BYTES = 8L * 1024L * 1024L
        internal const val DEFAULT_KNOWN_SIZE_CACHE_BYTES = 256L * 1024L
        internal const val DEFAULT_SUGGESTION_COUNT_CACHE_BYTES = 256L * 1024L
        internal const val DEFAULT_GALLERY_MANIFEST_CACHE_BYTES = 8L * 1024L * 1024L
        internal const val DEFAULT_GALLERY_MANIFEST_TTL_MILLIS = 2L * 60L * 1000L
        private val HITOMI_FEATURED_TYPES = listOf(
            "doujinshi",
            "manga",
            "artistcg",
            "gamecg",
            "imageset",
            "anime",
        )
        private val HITOMI_FEATURED_LANGUAGES = listOf(
            "japanese",
            "english",
            "chinese",
            "korean",
            "spanish",
            "french",
            "german",
            "italian",
            "russian",
        )

        private fun HitomiAutocompleteEntry.toFacetedSuggestion(): FacetedTagSuggestion {
            val facet = HITOMI_FACETS.facetOf(namespace)
                ?: throw HitomiProtocolException("Unsupported Hitomi namespace: $namespace")
            return FacetedTagSuggestion(
                text = name,
                facet = facet,
                sourceNamespace = namespace,
                count = count,
            )
        }

        private fun FacetedSearchScope.toHitomiAutocompleteScope(): String {
            val facet = facet ?: return "global"
            return sourceNamespace?.lowercase(Locale.ROOT) ?: HITOMI_FACETS.defaultNamespace(facet)
        }

        private fun FacetedTagSuggestion.termCacheKey(): String {
            return toSearchTerm().termCacheKey()
        }
    }
}
