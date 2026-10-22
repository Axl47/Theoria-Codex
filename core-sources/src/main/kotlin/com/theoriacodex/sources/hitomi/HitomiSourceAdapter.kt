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
import com.theoriacodex.domain.query.QueryHash
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.http.SourceHttpClient
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    override val supportedSearchScopes: Set<FacetedSearchScope> = linkedSetOf(
        FacetedSearchScope.All,
        FacetedSearchScope(SearchFacet.TAG, HITOMI_TAG_NAMESPACE),
        FacetedSearchScope(SearchFacet.TAG, HITOMI_FEMALE_NAMESPACE),
        FacetedSearchScope(SearchFacet.TAG, HITOMI_MALE_NAMESPACE),
        FacetedSearchScope(SearchFacet.ARTIST, HITOMI_ARTIST_NAMESPACE),
        FacetedSearchScope(SearchFacet.CHARACTER, HITOMI_CHARACTER_NAMESPACE),
        FacetedSearchScope(SearchFacet.SERIES, HITOMI_SERIES_NAMESPACE),
        FacetedSearchScope(SearchFacet.GROUP, HITOMI_GROUP_NAMESPACE),
        FacetedSearchScope(SearchFacet.TYPE, HITOMI_TYPE_NAMESPACE),
        FacetedSearchScope(SearchFacet.LANGUAGE, HITOMI_LANGUAGE_NAMESPACE),
    )

    private val postMapper = HitomiPostMapper(mediaUrlResolver, mediaCandidates)

    override suspend fun recoverPostMedia(post: Post, failedMedia: ImageRef): Post? =
        postMapper.recoverPostMedia(post, failedMedia)

    private val pageTokenCodec = HitomiPageTokenCodec()
    private val suggestionCounts = HitomiByteBudgetCache<String, Int>(
        maxBytes = suggestionCountCacheMaxBytes,
        weigh = { key, _ -> key.hitomiUtf8ByteWeight() + Int.SIZE_BYTES },
    )
    private val globalIndexCache = HitomiGlobalIndexCache(globalIndexCacheMaxBytes)
    private val queryCompiler = HitomiQueryCompiler(httpClient, globalIndexCache)
    private val nozomiReader = HitomiNozomiReader(
        httpClient, globalIndexCache, knownSizeCacheMaxBytes, membershipCacheMaxBytes,
    )
    private val randomSnapshotCache = HitomiRandomSnapshotCache(
        maxBytes = randomSnapshotCacheMaxBytes,
        initialReuseTtlMillis = randomSnapshotReuseTtlMillis,
        nowMillis = randomSnapshotNowMillis,
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

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        return try {
            searchInternal(query, pageToken)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SourceAdapterException) {
            throw error
        } catch (error: HitomiProtocolException) {
            throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "Hitomi search protocol failed: ${error.message}",
                cause = error,
            )
        }
    }

    private suspend fun searchInternal(query: Query, pageToken: String?): Page<Post> {
        val compiled = queryCompiler.compile(query)
        if (compiled.isUnsatisfiable) return Page(items = emptyList(), nextPageToken = null)

        val queryHash = QueryHash.from(query)
        val decodedToken = pageToken?.let(::decodePageToken)
        validatePageToken(decodedToken, queryHash, compiled.globalIndexVersion, query.sort)

        val primaryOptions = compiled.primaryOptions(query.sort)
        val primary = if (decodedToken == null) {
            choosePrimary(primaryOptions)
        } else {
            primaryOptions.firstOrNull { option -> option.key == decodedToken.primaryKey }
                ?: throw sourceParseFailure("Hitomi page token references an invalid primary index")
        }
        val seed = decodedToken?.randomSeed ?: deterministicSeed(queryHash)
        val primaryOffset = decodedToken?.primaryOffset ?: 0L
        if (primaryOffset < 0L) {
            throw sourceParseFailure("Hitomi page token contained a negative primary offset")
        }

        val secondaryIncludes = compiled.secondaryIncludes(primary.key)
            .map { index -> nozomiReader.membershipFor(index) }
        if (secondaryIncludes.any(IntArray::isEmpty)) {
            return Page(items = emptyList(), nextPageToken = null)
        }
        val exclusions = compiled.exclusions
            .map { index -> nozomiReader.membershipFor(index) }

        if (query.sort == SortMode.RANDOM) {
            return randomPage(
                queryHash = queryHash,
                primary = primary,
                seed = seed,
                initialOffset = primaryOffset,
                expectedSnapshotFingerprint = decodedToken?.randomSnapshotFingerprint,
                secondaryIncludes = secondaryIncludes,
                exclusions = exclusions,
                globalIndexVersion = compiled.globalIndexVersion,
            )
        }

        return orderedPage(
            queryHash = queryHash,
            primary = primary,
            seed = seed,
            initialOffset = primaryOffset,
            secondaryIncludes = secondaryIncludes,
            exclusions = exclusions,
            globalIndexVersion = compiled.globalIndexVersion,
        )
    }

    private suspend fun orderedPage(
        queryHash: String,
        primary: CompiledIndex,
        seed: Long,
        initialOffset: Long,
        secondaryIncludes: List<IntArray>,
        exclusions: List<IntArray>,
        globalIndexVersion: String?,
    ): Page<Post> {
        val scanned = nozomiReader.scanMatchingIds(primary, initialOffset, pageSize, secondaryIncludes, exclusions)
        val posts = hydrateGalleries(scanned.ids)
        val nextToken = if (!scanned.exhausted && scanned.scannedIds > 0) {
            encodePageToken(
                HitomiPageToken(
                    version = HITOMI_PAGE_TOKEN_VERSION,
                    queryHash = queryHash,
                    primaryKey = primary.key,
                    primaryOffset = scanned.nextOffset,
                    randomSeed = seed,
                    randomSnapshotFingerprint = null,
                    globalIndexVersion = globalIndexVersion,
                ),
            )
        } else {
            null
        }
        return Page(items = posts, nextPageToken = nextToken)
    }

    private fun validatePageToken(
        decodedToken: DecodedHitomiPageToken?,
        queryHash: String,
        globalIndexVersion: String?,
        sort: SortMode,
    ) {
        if (decodedToken != null && decodedToken.queryHash != queryHash) {
            throw sourceParseFailure("Hitomi page token does not match the active query")
        }
        if (decodedToken != null && decodedToken.globalIndexVersion != globalIndexVersion) {
            throw sourceParseFailure("Hitomi page token does not match the active global index version")
        }
        if (
            decodedToken != null &&
            sort == SortMode.RANDOM &&
            decodedToken.randomSnapshotFingerprint == null
        ) {
            throw sourceParseFailure("Hitomi random page token omitted its source snapshot")
        }
        if (
            decodedToken != null &&
            sort == SortMode.RANDOM &&
            decodedToken.randomPermutationVersion != HitomiDeterministicPermutation.ALGORITHM_VERSION
        ) {
            throw sourceParseFailure("Hitomi random page token used an unsupported permutation")
        }
        if (
            decodedToken != null &&
            sort != SortMode.RANDOM &&
            (decodedToken.randomSnapshotFingerprint != null || decodedToken.randomPermutationVersion != null)
        ) {
            throw sourceParseFailure("Hitomi non-random page token contained a random snapshot")
        }
    }

    private suspend fun randomPage(
        queryHash: String,
        primary: CompiledIndex,
        seed: Long,
        initialOffset: Long,
        expectedSnapshotFingerprint: String?,
        secondaryIncludes: List<IntArray>,
        exclusions: List<IntArray>,
        globalIndexVersion: String?,
    ): Page<Post> {
        val snapshot = randomSnapshotFor(
            compiledIndex = primary,
            expectedSnapshotFingerprint = expectedSnapshotFingerprint,
        )
        val randomIds = snapshot.ids
        if (initialOffset > randomIds.size.toLong()) {
            throw sourceParseFailure("Hitomi page token offset exceeded the random candidate list")
        }
        val permutation = randomIds.takeIf(IntArray::isNotEmpty)?.let { ids ->
            HitomiDeterministicPermutation(ids.size, seed)
        }
        var offset = initialOffset
        var scanned = 0
        val survivingIds = HitomiIntArrayBuilder(pageSize)
        while (
            offset < randomIds.size.toLong() &&
            survivingIds.size < pageSize &&
            scanned < MAX_PRIMARY_SCAN_IDS_PER_PAGE
        ) {
            val galleryId = randomIds[requireNotNull(permutation).sourceIndex(offset)]
            offset += 1L
            scanned += 1
            if (matchesHitomiMemberships(galleryId, secondaryIncludes, exclusions)) survivingIds.add(galleryId)
        }

        val posts = hydrateGalleries(survivingIds.toIntArray())
        val nextToken = if (offset < randomIds.size.toLong()) {
            encodePageToken(
                HitomiPageToken(
                    version = HITOMI_PAGE_TOKEN_VERSION,
                    queryHash = queryHash,
                    primaryKey = primary.key,
                    primaryOffset = offset,
                    randomSeed = seed,
                    randomSnapshotFingerprint = snapshot.fingerprint,
                    randomPermutationVersion = HitomiDeterministicPermutation.ALGORITHM_VERSION,
                    globalIndexVersion = globalIndexVersion,
                ),
            )
        } else {
            null
        }
        return Page(items = posts, nextPageToken = nextToken)
    }

    private suspend fun randomSnapshotFor(
        compiledIndex: CompiledIndex,
        expectedSnapshotFingerprint: String?,
    ): HitomiNozomiSnapshot {
        val url = compiledIndex.url
        val snapshot = try {
            randomSnapshotCache.getOrLoad(url, expectedSnapshotFingerprint) {
                nozomiReader.readCompleteNozomi(compiledIndex, MAX_RANDOM_GALLERY_IDS)
            }
        } catch (error: HitomiRandomSnapshotMismatchException) {
            throw sourceParseFailure(
                "Hitomi random source changed before the next page could be resumed",
                error,
            )
        }
        if (
            expectedSnapshotFingerprint != null &&
            snapshot.fingerprint != expectedSnapshotFingerprint
        ) {
            throw sourceParseFailure(
                "Hitomi random source changed before the next page could be resumed",
            )
        }
        return snapshot
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

    override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> =
        autocompleteTagCounts(sourceKey, tags, ::autocompleteTags)
    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        return autocompleteFaceted(
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
    ): List<FacetedTagSuggestion> {
        return try {
            autocompleteFacetedInternal(prefix, scope, limit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SourceAdapterException) {
            throw error
        } catch (error: HitomiProtocolException) {
            throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "Hitomi autocomplete protocol failed: ${error.message}",
                cause = error,
            )
        }
    }

    override suspend fun featuredFacetedSuggestions(
        scope: FacetedSearchScope,
        limit: Int,
    ): List<FacetedTagSuggestion> {
        if (limit <= 0 || scope !in supportedSearchScopes) return emptyList()
        val values = when (scope.facet) {
            SearchFacet.TYPE -> HITOMI_FEATURED_TYPES
            SearchFacet.LANGUAGE -> HITOMI_FEATURED_LANGUAGES
            else -> emptyList()
        }
        return values.take(limit).map { value ->
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
            throw sourceHttpFailure("autocomplete", response.statusCode)
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

    override suspend fun quickQuery(kind: QuickQueryKind): Query {
        val sort = when (kind) {
            QuickQueryKind.POPULAR_TODAY -> SortMode.POPULAR
            QuickQueryKind.TOP_7D, QuickQueryKind.TOP_30D -> SortMode.TOP
            QuickQueryKind.NEWEST -> SortMode.NEWEST
            QuickQueryKind.RANDOM -> SortMode.RANDOM
        }
        return Query(
            mode = QueryMode.Source(SourceKey.HITOMI),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = sort,
            dateRange = null,
            minScore = null,
        )
    }

    override suspend fun resolvePost(id: PostId): Post? {
        if (id.source != SourceKey.HITOMI) return null
        val galleryId = id.sourcePostId.trim().toIntOrNull()?.takeIf { value -> value > 0 }
            ?: return null
        return try {
            fetchGallery(galleryId, sparse = false)
        } catch (error: HitomiGalleryException) {
            throw SourceAdapterException(
                reason = error.reason,
                message = error.message,
                cause = error.cause ?: error,
            )
        }
    }

    override suspend fun searchCreatorPosts(
        creator: CreatorProfile,
        pageToken: String?,
    ): Page<Post> {
        val artist = creator.canonicalHitomiArtistIdentity()
            ?: return Page(items = emptyList(), nextPageToken = null)
        return search(
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

    private suspend fun choosePrimary(options: List<CompiledIndex>): CompiledIndex {
        require(options.isNotEmpty()) { "Hitomi search requires a primary index" }
        if (options.size == 1) return options.single()
        val estimates = options.associateWith { option ->
            option.inlineIds?.size?.toLong()
                ?: option.term
                ?.termCacheKey()
                ?.let(suggestionCounts::get)
                ?.toLong()
                ?: nozomiReader.nozomiSize(option.url)
        }
        return options.minWithOrNull(
            compareBy<CompiledIndex> { option -> estimates.getValue(option) }
                .thenBy(CompiledIndex::key),
        ) ?: options.first()
    }

    private suspend fun hydrateGalleries(ids: IntArray): List<Post> = supervisorScope {
        val semaphore = Semaphore(hydrationConcurrency)
        ids.map { galleryId ->
            async {
                semaphore.withPermit {
                    try {
                        fetchGallery(galleryId, sparse = true)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: HitomiGalleryException) {
                        null
                    }
                }
            }
        }.awaitAll()
            .filterNotNull()
            .distinctBy(Post::id)
    }

    private suspend fun fetchGallery(galleryId: Int, sparse: Boolean): Post? {
        val gallery = galleryManifests.getOrLoad(galleryId) { loadGalleryManifest(galleryId) }
            ?: return null
        if (gallery.intValue("blocked") == 1) return null
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
        if (gallery.intValue("blocked") == 1) return null
        return HitomiGalleryManifest(
            gallery = gallery,
            payloadBytes = response.body.hitomiUtf8ByteWeight(),
        )
    }

    private suspend fun requestText(url: String) = try {
        httpClient.get(url = url, headers = HitomiProtocol.requestHeaders)
    } catch (error: IOException) {
        throw SourceAdapterException(
            reason = SourceFailureReason.NETWORK,
            message = "Hitomi request failed ($url)",
            cause = error,
        )
    }

    private fun encodePageToken(token: HitomiPageToken): String {
        return pageTokenCodec.encode(token)
    }

    private fun decodePageToken(raw: String): DecodedHitomiPageToken {
        return try {
            pageTokenCodec.decode(raw)
        } catch (error: HitomiPageTokenException) {
            throw sourceParseFailure(error.message ?: "Hitomi page token was invalid", error)
        }
    }

    private fun sourceHttpFailure(step: String, statusCode: Int): SourceAdapterException {
        return SourceAdapterException(
            reason = classifyHttpFailure(statusCode),
            message = "Hitomi $step request failed ($statusCode)",
        )
    }

    private fun sourceParseFailure(message: String, cause: Throwable? = null): SourceAdapterException {
        return SourceAdapterException(
            reason = SourceFailureReason.PARSE,
            message = message,
            cause = cause,
        )
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 25
        private const val MAX_PAGE_SIZE = 100
        private const val DEFAULT_HYDRATION_CONCURRENCY = 4
        private const val MAX_HYDRATION_CONCURRENCY = 8
        private const val HITOMI_AUTOCOMPLETE_LIMIT = 10
        private const val MAX_PRIMARY_SCAN_IDS_PER_PAGE = 10_000
        internal const val DEFAULT_MEMBERSHIP_CACHE_BYTES = 8L * 1024L * 1024L
        private const val MAX_RANDOM_GALLERY_IDS = HitomiNozomi.MAX_GALLERY_IDS
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

        private fun deterministicSeed(queryHash: String): Long {
            val digest = MessageDigest.getInstance("SHA-256").digest(queryHash.toByteArray(Charsets.UTF_8))
            return digest.take(Long.SIZE_BYTES).fold(0L) { result, byte ->
                (result shl Byte.SIZE_BITS) or (byte.toLong() and 0xffL)
            }
        }

        private fun HitomiAutocompleteEntry.toFacetedSuggestion(): FacetedTagSuggestion {
            val facet = when (namespace) {
                HITOMI_TAG_NAMESPACE, HITOMI_FEMALE_NAMESPACE, HITOMI_MALE_NAMESPACE -> SearchFacet.TAG
                HITOMI_ARTIST_NAMESPACE -> SearchFacet.ARTIST
                HITOMI_CHARACTER_NAMESPACE -> SearchFacet.CHARACTER
                HITOMI_SERIES_NAMESPACE -> SearchFacet.SERIES
                HITOMI_GROUP_NAMESPACE -> SearchFacet.GROUP
                HITOMI_TYPE_NAMESPACE -> SearchFacet.TYPE
                HITOMI_LANGUAGE_NAMESPACE -> SearchFacet.LANGUAGE
                else -> throw HitomiProtocolException("Unsupported Hitomi namespace: $namespace")
            }
            return FacetedTagSuggestion(
                text = name,
                facet = facet,
                sourceNamespace = namespace,
                count = count,
            )
        }

        private fun FacetedSearchScope.toHitomiAutocompleteScope(): String {
            if (isAll) return "global"
            return sourceNamespace?.lowercase(Locale.ROOT) ?: when (facet) {
                SearchFacet.TAG -> HITOMI_TAG_NAMESPACE
                SearchFacet.ARTIST -> HITOMI_ARTIST_NAMESPACE
                SearchFacet.CHARACTER -> HITOMI_CHARACTER_NAMESPACE
                SearchFacet.SERIES -> HITOMI_SERIES_NAMESPACE
                SearchFacet.GROUP -> HITOMI_GROUP_NAMESPACE
                SearchFacet.TYPE -> HITOMI_TYPE_NAMESPACE
                SearchFacet.LANGUAGE -> HITOMI_LANGUAGE_NAMESPACE
                null -> "global"
            }
        }

        private fun FacetedTagSuggestion.termCacheKey(): String {
            return toSearchTerm().termCacheKey()
        }
    }
}
