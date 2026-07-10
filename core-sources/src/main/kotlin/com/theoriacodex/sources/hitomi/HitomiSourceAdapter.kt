package com.theoriacodex.sources.hitomi

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.HITOMI_ARTIST_QUERY_PREFIX
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.canonicalHitomiArtistIdentity
import com.theoriacodex.domain.query.QueryHash
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpBodyTooLargeException
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.media.mimeFromFileExt
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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
    private val mediaCandidates: suspend (HitomiMediaFile) -> List<HitomiMediaCandidate> = { file ->
        mediaUrlResolver.candidates(file)
    },
) : SourceAdapter, FacetedSearchSourceAdapter, CreatorPostsSourceAdapter, MediaRecoverySourceAdapter {
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

    private val gson = Gson()
    private val suggestionCounts = ConcurrentHashMap<String, Int>()
    private val knownNozomiSizes = ConcurrentHashMap<String, Long>()
    private val membershipCacheMutex = Mutex()
    private val membershipCache = object : LinkedHashMap<String, IntArray>(
        MAX_MEMBERSHIP_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?): Boolean {
            return size > MAX_MEMBERSHIP_CACHE_ENTRIES
        }
    }
    private val randomOrderCacheMutex = Mutex()
    private val randomOrderCache = object : LinkedHashMap<RandomOrderCacheKey, IntArray>(
        MAX_RANDOM_ORDER_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<RandomOrderCacheKey, IntArray>?,
        ): Boolean = size > MAX_RANDOM_ORDER_CACHE_ENTRIES
    }

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
        val compiled = compileQuery(query)
        if (compiled.isUnsatisfiable) return Page(items = emptyList(), nextPageToken = null)

        val queryHash = QueryHash.from(query)
        val decodedToken = pageToken?.let(::decodePageToken)
        if (decodedToken != null && decodedToken.queryHash != queryHash) {
            throw sourceParseFailure("Hitomi page token does not match the active query")
        }
        if (
            decodedToken != null &&
            query.sort == SortMode.RANDOM &&
            decodedToken.randomSnapshotFingerprint == null
        ) {
            throw sourceParseFailure("Hitomi random page token omitted its source snapshot")
        }
        if (
            decodedToken != null &&
            query.sort != SortMode.RANDOM &&
            decodedToken.randomSnapshotFingerprint != null
        ) {
            throw sourceParseFailure("Hitomi non-random page token contained a random snapshot")
        }

        val primaryOptions = compiled.primaryOptions(query.sort)
        val primary = if (decodedToken == null) {
            choosePrimary(primaryOptions)
        } else {
            primaryOptions.firstOrNull { option -> option.key == decodedToken.primaryKey }
                ?: throw sourceParseFailure("Hitomi page token references an invalid primary index")
        }
        val seed = decodedToken?.randomSeed ?: deterministicSeed(queryHash)
        var primaryOffset = decodedToken?.primaryOffset ?: 0L
        if (primaryOffset < 0L) {
            throw sourceParseFailure("Hitomi page token contained a negative primary offset")
        }

        val secondaryIncludes = compiled.secondaryIncludes(primary.key)
            .map { index -> membershipFor(index.url) }
        if (secondaryIncludes.any(IntArray::isEmpty)) {
            return Page(items = emptyList(), nextPageToken = null)
        }
        val exclusions = compiled.exclusions
            .map { index -> membershipFor(index.url) }

        if (query.sort == SortMode.RANDOM) {
            return randomPage(
                queryHash = queryHash,
                primary = primary,
                seed = seed,
                initialOffset = primaryOffset,
                expectedSnapshotFingerprint = decodedToken?.randomSnapshotFingerprint,
                secondaryIncludes = secondaryIncludes,
                exclusions = exclusions,
            )
        }

        val survivingIds = ArrayList<Int>(pageSize)
        var exhausted = false
        var scannedIds = 0
        while (
            survivingIds.size < pageSize &&
            !exhausted &&
            scannedIds < MAX_PRIMARY_SCAN_IDS_PER_PAGE
        ) {
            val needed = pageSize - survivingIds.size
            val requestedIds = max(needed, PRIMARY_SCAN_CHUNK_IDS)
                .coerceAtMost(MAX_PRIMARY_SCAN_IDS_PER_PAGE - scannedIds)

            val range = readPrimaryRange(
                url = primary.url,
                firstIdIndex = primaryOffset,
                idCount = requestedIds,
            )
            if (range.ids.isEmpty()) {
                exhausted = true
                break
            }

            var consumedFromRange = 0
            for (galleryId in range.ids) {
                consumedFromRange += 1
                scannedIds += 1
                val included = secondaryIncludes.all { membership -> membership.containsId(galleryId) }
                val excluded = exclusions.any { membership -> membership.containsId(galleryId) }
                if (included && !excluded) {
                    survivingIds += galleryId
                }
                if (survivingIds.size >= pageSize) break
            }

            primaryOffset += consumedFromRange
            exhausted = range.exhausted && consumedFromRange >= range.ids.size
        }

        val posts = hydrateGalleries(survivingIds)
        val nextToken = if (!exhausted && scannedIds > 0) {
            encodePageToken(
                HitomiPageToken(
                    version = PAGE_TOKEN_VERSION,
                    queryHash = queryHash,
                    primaryKey = primary.key,
                    primaryOffset = primaryOffset,
                    randomSeed = seed,
                    randomSnapshotFingerprint = null,
                ),
            )
        } else {
            null
        }
        return Page(items = posts, nextPageToken = nextToken)
    }

    private suspend fun randomPage(
        queryHash: String,
        primary: CompiledIndex,
        seed: Long,
        initialOffset: Long,
        expectedSnapshotFingerprint: String?,
        secondaryIncludes: List<IntArray>,
        exclusions: List<IntArray>,
    ): Page<Post> {
        val randomOrder = randomOrderFor(
            url = primary.url,
            seed = seed,
            expectedSnapshotFingerprint = expectedSnapshotFingerprint,
        )
        val randomIds = randomOrder.ids
        if (initialOffset > randomIds.size.toLong()) {
            throw sourceParseFailure("Hitomi page token offset exceeded the random candidate list")
        }
        var offset = initialOffset.toInt()
        var scanned = 0
        val survivingIds = ArrayList<Int>(pageSize)
        while (
            offset < randomIds.size &&
            survivingIds.size < pageSize &&
            scanned < MAX_PRIMARY_SCAN_IDS_PER_PAGE
        ) {
            val galleryId = randomIds[offset]
            offset += 1
            scanned += 1
            val included = secondaryIncludes.all { membership -> membership.containsId(galleryId) }
            val excluded = exclusions.any { membership -> membership.containsId(galleryId) }
            if (included && !excluded) survivingIds += galleryId
        }

        val posts = hydrateGalleries(survivingIds)
        val nextToken = if (offset < randomIds.size) {
            encodePageToken(
                HitomiPageToken(
                    version = PAGE_TOKEN_VERSION,
                    queryHash = queryHash,
                    primaryKey = primary.key,
                    primaryOffset = offset.toLong(),
                    randomSeed = seed,
                    randomSnapshotFingerprint = randomOrder.snapshotFingerprint,
                ),
            )
        } else {
            null
        }
        return Page(items = posts, nextPageToken = nextToken)
    }

    private suspend fun randomOrderFor(
        url: String,
        seed: Long,
        expectedSnapshotFingerprint: String?,
    ): RandomOrder {
        if (expectedSnapshotFingerprint != null) {
            randomOrderCacheMutex.withLock {
                val key = RandomOrderCacheKey(url, seed, expectedSnapshotFingerprint)
                randomOrderCache[key]?.let { cachedIds ->
                    return RandomOrder(
                        ids = cachedIds,
                        snapshotFingerprint = key.snapshotFingerprint,
                    )
                }
            }
        }

        val snapshot = readCompleteNozomi(url, MAX_RANDOM_GALLERY_IDS)
        if (
            expectedSnapshotFingerprint != null &&
            snapshot.fingerprint != expectedSnapshotFingerprint
        ) {
            throw sourceParseFailure(
                "Hitomi random source changed before the next page could be resumed",
            )
        }
        val ids = snapshot.ids
        val random = Random(seed)
        for (index in ids.lastIndex downTo 1) {
            val swapIndex = random.nextInt(index + 1)
            val value = ids[index]
            ids[index] = ids[swapIndex]
            ids[swapIndex] = value
        }
        val key = RandomOrderCacheKey(url, seed, snapshot.fingerprint)
        randomOrderCacheMutex.withLock {
            randomOrderCache[key] = ids
        }
        return RandomOrder(ids = ids, snapshotFingerprint = snapshot.fingerprint)
    }

    private suspend fun readCompleteNozomi(url: String, maxGalleryIds: Int): NozomiSnapshot {
        val maxBytes = Math.multiplyExact(maxGalleryIds, Int.SIZE_BYTES)
        val response = requestNozomi(
            url = url,
            firstIdIndex = 0L,
            idCount = maxGalleryIds,
            maxBodyBytes = maxBytes,
        )
        val body = when (response.statusCode) {
            404, 416 -> ByteArray(0)
            200 -> response.body
            206 -> {
                val contentRange = response.validatedContentRange()
                    ?: throw sourceParseFailure("Hitomi random index omitted its byte range")
                val totalBytes = contentRange.totalBytes
                    ?: throw sourceParseFailure("Hitomi random index omitted its total size")
                if (
                    contentRange.startByte != 0L ||
                    contentRange.endByte + 1L != totalBytes ||
                    response.body.size.toLong() != totalBytes
                ) {
                    throw sourceParseFailure(
                        "Hitomi random index returned only part of its declared snapshot",
                    )
                }
                if (bytesToIdCount(totalBytes) > maxGalleryIds) {
                    throw sourceParseFailure("Hitomi random index exceeded the bounded gallery limit")
                }
                response.body
            }
            else -> throw sourceHttpFailure("random Nozomi", response.statusCode)
        }
        val ids = HitomiNozomi.decodeGalleryIds(body, maxGalleryIds).toIntArray()
        return NozomiSnapshot(ids = ids, fingerprint = sha256Hex(body))
    }

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
                count = suggestionCounts[SearchTerm(
                    value = value,
                    facet = requireNotNull(scope.facet),
                    sourceNamespace = scope.sourceNamespace,
                ).termCacheKey()],
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
                        suggestionCounts[suggestion.termCacheKey()] = count
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

    override suspend fun recoverPostMedia(post: Post, failedMedia: ImageRef): Post? {
        if (post.id.source != SourceKey.HITOMI) return null
        val identity = failedMedia.hitomiRecoveryIdentityOrNull() ?: return null
        val refreshedCandidates = mediaUrlResolver.refreshCandidates(
            file = HitomiMediaFile(
                hash = identity.hash,
                name = "${identity.hash}.${identity.originalExtension}",
                hasAvif = identity.hasAvif,
            ),
            failedConfigurationVersion = identity.configurationVersion,
        )
        if (refreshedCandidates.isEmpty()) return null

        var replaced = false
        fun recover(ref: ImageRef): ImageRef {
            val refIdentity = ref.hitomiRecoveryIdentityOrNull() ?: return ref
            if (refIdentity.hash != identity.hash) return ref
            val refCandidates = refreshedCandidates.preferFormat(refIdentity.failedFormat)
            if (refCandidates.isEmpty()) return ref
            replaced = true
            return ref.copy(
                url = refCandidates.first().url,
                mime = refCandidates.first().mime,
                progressiveUrls = refCandidates.map(HitomiMediaCandidate::url).distinct(),
            )
        }

        val recovered = post.copy(
            preview = recover(post.preview),
            full = post.full?.let(::recover),
            media = post.media.map(::recover),
        )
        return recovered.takeIf { replaced }
    }

    private suspend fun choosePrimary(options: List<CompiledIndex>): CompiledIndex {
        require(options.isNotEmpty()) { "Hitomi search requires a primary index" }
        if (options.size == 1) return options.single()
        val estimates = options.associateWith { option ->
            option.term
                ?.termCacheKey()
                ?.let(suggestionCounts::get)
                ?.toLong()
                ?: nozomiSize(option.url)
        }
        return options.minWithOrNull(
            compareBy<CompiledIndex> { option -> estimates.getValue(option) }
                .thenBy(CompiledIndex::key),
        ) ?: options.first()
    }

    private suspend fun nozomiSize(url: String): Long {
        knownNozomiSizes[url]?.let { return it }
        val response = requestNozomi(
            url = url,
            firstIdIndex = 0L,
            idCount = 1,
        )
        val size = when (response.statusCode) {
            404, 416 -> 0L
            200 -> HitomiNozomi.decodeGalleryIds(response.body).size.toLong()
            206 -> {
                validateNozomiBody(response.body)
                response.validatedContentRange()
                    ?.totalBytes
                    ?.let(::bytesToIdCount)
                    ?: throw sourceParseFailure("Hitomi Nozomi size probe omitted its total length")
            }
            else -> throw sourceHttpFailure("Nozomi size probe", response.statusCode)
        }
        knownNozomiSizes[url] = size
        return size
    }

    private suspend fun membershipFor(url: String): IntArray {
        membershipCacheMutex.withLock {
            membershipCache[url]?.let { return it }
        }

        val response = requestNozomi(
            url = url,
            firstIdIndex = 0L,
            idCount = MAX_SECONDARY_MEMBERSHIP_IDS,
            maxBodyBytes = MAX_SECONDARY_MEMBERSHIP_IDS * Int.SIZE_BYTES,
        )
        val ids = when (response.statusCode) {
            404, 416 -> emptyList()
            200 -> HitomiNozomi.decodeGalleryIds(
                bytes = response.body,
                maxGalleryIds = MAX_SECONDARY_MEMBERSHIP_IDS,
            )
            206 -> {
                val contentRange = response.validatedContentRange()
                    ?: throw sourceParseFailure(
                        "Hitomi secondary index did not report a complete byte range",
                    )
                if (contentRange.startByte != 0L) {
                    throw sourceParseFailure("Hitomi secondary index did not start at byte zero")
                }
                val totalBytes = contentRange.totalBytes
                    ?: throw sourceParseFailure("Hitomi secondary index omitted its total length")
                val totalIds = bytesToIdCount(totalBytes)
                if (totalIds > MAX_SECONDARY_MEMBERSHIP_IDS) {
                    throw sourceParseFailure(
                        "Hitomi secondary index exceeded the bounded membership limit",
                    )
                }
                if (response.body.size.toLong() != totalBytes) {
                    throw sourceParseFailure("Hitomi secondary index response was incomplete")
                }
                val decoded = HitomiNozomi.decodeGalleryIds(
                    bytes = response.body,
                    maxGalleryIds = MAX_SECONDARY_MEMBERSHIP_IDS,
                )
                decoded
            }
            else -> throw sourceHttpFailure("Nozomi membership", response.statusCode)
        }
        val membership = ids.distinct().sorted().toIntArray()
        membershipCacheMutex.withLock {
            membershipCache[url] = membership
        }
        return membership
    }

    private suspend fun readPrimaryRange(
        url: String,
        firstIdIndex: Long,
        idCount: Int,
    ): PrimaryRange {
        val response = requestNozomi(url, firstIdIndex, idCount)
        return when (response.statusCode) {
            404, 416 -> PrimaryRange(emptyList(), exhausted = true)
            200 -> {
                val allIds = HitomiNozomi.decodeGalleryIds(response.body)
                val from = firstIdIndex.coerceAtMost(allIds.size.toLong()).toInt()
                val until = (firstIdIndex + idCount)
                    .coerceAtMost(allIds.size.toLong())
                    .toInt()
                PrimaryRange(
                    ids = allIds.subList(from, until),
                    exhausted = until >= allIds.size,
                )
            }
            206 -> {
                val contentRange = response.validatedContentRange()
                    ?: throw sourceParseFailure("Hitomi Nozomi page omitted its byte range")
                val expectedStartByte = Math.multiplyExact(firstIdIndex, Int.SIZE_BYTES.toLong())
                if (contentRange.startByte != expectedStartByte) {
                    throw sourceParseFailure("Hitomi Nozomi response started at an unexpected byte")
                }
                val ids = HitomiNozomi.decodeGalleryIds(response.body)
                val totalIds = contentRange.totalBytes?.let(::bytesToIdCount)
                if (totalIds != null) knownNozomiSizes[url] = totalIds
                PrimaryRange(
                    ids = ids,
                    exhausted = when {
                        totalIds != null -> firstIdIndex + ids.size >= totalIds
                        else -> ids.size < idCount
                    },
                )
            }
            else -> throw sourceHttpFailure("Nozomi page", response.statusCode)
        }
    }

    private fun SourceByteResponse.validatedContentRange(): ParsedContentRange? {
        val parsed = contentRange() ?: return null
        val bodyLength = parsed.endByte - parsed.startByte + 1L
        if (
            parsed.startByte < 0L ||
            parsed.endByte < parsed.startByte ||
            parsed.startByte % Int.SIZE_BYTES != 0L ||
            (parsed.endByte + 1L) % Int.SIZE_BYTES != 0L ||
            bodyLength != body.size.toLong() ||
            (parsed.totalBytes != null && parsed.totalBytes < parsed.endByte + 1L)
        ) {
            throw sourceParseFailure("Hitomi Nozomi response reported an invalid byte range")
        }
        parsed.totalBytes?.let(::bytesToIdCount)
        return parsed
    }

    private suspend fun requestNozomi(
        url: String,
        firstIdIndex: Long,
        idCount: Int,
        maxBodyBytes: Int = MAX_NOZOMI_RESPONSE_BYTES,
    ): SourceByteResponse {
        return try {
            httpClient.getBytes(
                url = url,
                headers = HitomiProtocol.requestHeaders,
                range = HitomiNozomi.byteRangeForIds(firstIdIndex, idCount),
                maxBodyBytes = maxBodyBytes,
            )
        } catch (error: SourceHttpBodyTooLargeException) {
            throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "Hitomi Nozomi response ignored or exceeded its bounded byte range ($url)",
                cause = error,
            )
        } catch (error: IOException) {
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "Hitomi Nozomi request failed ($url)",
                cause = error,
            )
        }
    }

    private suspend fun hydrateGalleries(ids: List<Int>): List<Post> = supervisorScope {
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
        }.awaitAll().filterNotNull()
    }

    private suspend fun fetchGallery(galleryId: Int, sparse: Boolean): Post? {
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
        return gallery.toPost(galleryId, sparse)
    }

    private suspend fun JsonObject.toPost(fallbackGalleryId: Int, sparse: Boolean): Post {
        val galleryId = stringValue("id")?.toIntOrNull() ?: fallbackGalleryId
        val taxonomy = parseTaxonomy()
        val files = arrayValue("files")
            ?.mapIndexed { index, element ->
                element.asObjectOrNull()?.toHitomiMediaFile()
                    ?: throw HitomiGalleryException(
                        reason = SourceFailureReason.PARSE,
                        message = "Hitomi gallery $galleryId had invalid file metadata at index $index",
                    )
            }
            .orEmpty()
        val animated = taxonomy.any { term ->
            term.facet == SearchFacet.TAG && term.value.equals("animated", ignoreCase = true)
        }
        val type = stringValue("type")?.lowercase(Locale.ROOT)
        val videoFilename = stringValue("videofilename")
        val hasPlayableVideo = type == HITOMI_ANIME_TYPE && videoFilename != null
        if (files.isEmpty() && !hasPlayableVideo) {
            throw HitomiGalleryException(
                reason = SourceFailureReason.PARSE,
                message = "Hitomi gallery $galleryId did not contain usable media files",
            )
        }

        val posterFile = if (type == HITOMI_ANIME_TYPE && videoFilename != null) {
            files.lastOrNull()
        } else {
            files.firstOrNull()
        }
        val filesToResolve = if (sparse || (type == HITOMI_ANIME_TYPE && videoFilename != null)) {
            listOfNotNull(posterFile)
        } else {
            files
        }
        val resolvedCandidates = filesToResolve.associateWith { file -> candidatesFor(file) }
        val poster = posterFile?.let { file ->
            imageRef(
                file = file,
                candidates = resolvedCandidates[file].orEmpty(),
                animated = animated,
                preview = true,
            )
        } ?: EMPTY_IMAGE_REF
        val imageMedia = when {
            sparse || (type == HITOMI_ANIME_TYPE && videoFilename != null) -> emptyList()
            else -> files.map { file ->
                imageRef(
                    file = file,
                    candidates = resolvedCandidates[file].orEmpty(),
                    animated = animated,
                    preview = false,
                )
            }
        }
        val videoMedia = if (type == HITOMI_ANIME_TYPE && videoFilename != null) {
            listOf(
                ImageRef(
                    url = hitomiVideoUrl(videoFilename),
                    localPath = null,
                    mime = "video/mp4",
                    progressiveUrls = emptyList(),
                ),
            )
        } else {
            emptyList()
        }
        val media = if (sparse) emptyList() else videoMedia.ifEmpty { imageMedia }
        val declaredMediaCount = if (type == HITOMI_ANIME_TYPE && videoFilename != null) {
            1
        } else {
            files.size
        }
        val creatorProfiles = parseCreatorProfiles()
        val primaryCreator = creatorProfiles.firstOrNull()
        val canonicalTags = taxonomy
            .filter { term -> term.facet == SearchFacet.TAG }
            .map(PostTaxonomyTerm::value)
            .distinct()
        val galleryPath = stringValue("galleryurl")
        val width = posterFile?.width
        val height = posterFile?.height

        return Post(
            id = PostId(SourceKey.HITOMI, galleryId.toString()),
            preview = poster,
            full = media.firstOrNull(),
            media = media,
            pageUrl = galleryPath?.let { path -> "https://hitomi.la/${path.trimStart('/')}" },
            width = width,
            height = height,
            canonicalTags = canonicalTags,
            rawTags = canonicalTags,
            authorName = primaryCreator?.displayName,
            createdAtEpochMs = parseHitomiDate(stringValue("date"), stringValue("datepublished")),
            title = stringValue("title")
                ?: stringValue("japanese_title")
                ?: "Hitomi #$galleryId",
            creatorProfile = primaryCreator,
            mediaCount = declaredMediaCount,
            taxonomy = taxonomy,
            creatorProfiles = creatorProfiles,
        )
    }

    private fun JsonObject.parseCreatorProfiles(): List<CreatorProfile> {
        return arrayValue("artists")
            ?.mapNotNull { element ->
                val artist = element.asObjectOrNull() ?: return@mapNotNull null
                val rawDisplayName = artist.untrimmedStringValue("artist") ?: return@mapNotNull null
                val identity = canonicalHitomiArtistIdentity(rawDisplayName) ?: return@mapNotNull null
                CreatorProfile(
                    source = SourceKey.HITOMI,
                    displayName = rawDisplayName.trim(),
                    profileId = identity,
                    profileUrl = canonicalHitomiArtistUrl(
                        artistIdentity = identity,
                        providerRelativeUrl = artist.stringValue("url"),
                    ),
                    uploadsQuery = "$HITOMI_ARTIST_QUERY_PREFIX$identity",
                )
            }
            .orEmpty()
            .distinctBy { creator -> creator.profileId }
    }

    private fun canonicalHitomiArtistUrl(
        artistIdentity: String,
        providerRelativeUrl: String?,
    ): String {
        providerRelativeUrl
            ?.takeIf { value -> value.isSafeHitomiArtistPathFor(artistIdentity) }
            ?.let { value -> return "https://hitomi.la$value" }
        val encoded = URLEncoder.encode(artistIdentity, Charsets.UTF_8.name()).replace("+", "%20")
        return "https://hitomi.la/artist/$encoded-all.html"
    }

    private fun String.isSafeHitomiArtistPathFor(artistIdentity: String): Boolean {
        if ('+' in this) return false
        val parsed = runCatching { URI(this) }.getOrNull() ?: return false
        if (parsed.isAbsolute || parsed.host != null || parsed.rawQuery != null || parsed.rawFragment != null) {
            return false
        }
        val path = parsed.rawPath ?: return false
        if (!path.startsWith("/artist/") || !path.endsWith("-all.html")) return false
        val encodedSlug = path.removePrefix("/artist/").removeSuffix("-all.html")
        val decodedSlug = decodeHitomiArtistPathSegmentStrict(encodedSlug) ?: return false
        return canonicalHitomiArtistIdentity(decodedSlug) == artistIdentity
    }

    private fun decodeHitomiArtistPathSegmentStrict(value: String): String? {
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '%') {
                if (index + 2 >= value.length) return null
                val high = value[index + 1].digitToIntOrNull(radix = 16) ?: return null
                val low = value[index + 2].digitToIntOrNull(radix = 16) ?: return null
                bytes.write((high shl 4) or low)
                index += 3
            } else {
                val codePoint = value.codePointAt(index)
                if (codePoint in 0xD800..0xDFFF) return null
                val encoded = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
                bytes.write(encoded)
                index += Character.charCount(codePoint)
            }
        }
        return runCatching {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        }.getOrNull()
    }

    private suspend fun candidatesFor(file: ParsedHitomiFile): List<HitomiMediaCandidate> {
        val candidates = try {
            mediaCandidates(
                HitomiMediaFile(
                    hash = file.hash,
                    name = file.name,
                    hasAvif = file.hasAvif,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SourceAdapterException) {
            throw error
        } catch (error: IOException) {
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "Hitomi media URL configuration request failed",
                cause = error,
            )
        } catch (error: Exception) {
            throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "Hitomi media URL configuration was invalid",
                cause = error,
            )
        }
        if (candidates.isEmpty()) {
            throw sourceParseFailure("Hitomi media URL resolver returned no candidates")
        }
        return candidates
    }

    private fun imageRef(
        file: ParsedHitomiFile,
        candidates: List<HitomiMediaCandidate>,
        animated: Boolean,
        preview: Boolean,
    ): ImageRef {
        val first = if (preview || animated) {
            candidates.firstOrNull { candidate -> candidate.format == HitomiMediaFormat.WEBP }
                ?: candidates.firstOrNull { candidate -> candidate.format == HitomiMediaFormat.ORIGINAL }
                ?: candidates.firstOrNull()
        } else {
            candidates.firstOrNull()
        }
        val orderedCandidates = first
            ?.let { preferred -> candidates.preferFormat(preferred.format) }
            .orEmpty()
        return ImageRef(
            url = first?.url,
            localPath = null,
            mime = first?.mime ?: mimeFromFileExt(file.name.substringAfterLast('.', "")),
            progressiveUrls = orderedCandidates.map(HitomiMediaCandidate::url).distinct(),
            isAnimated = animated,
        )
    }

    private fun ImageRef.hitomiRecoveryIdentityOrNull(): HitomiMediaRecoveryIdentity? {
        val locations = buildList {
            url?.takeIf(String::isNotBlank)?.let(::add)
            addAll(progressiveUrls.filter(String::isNotBlank))
        }.distinct()
        val failed = locations.firstOrNull()?.toHitomiCandidateIdentityOrNull() ?: return null
        val parsedLocations = locations.mapNotNull { location ->
            location.toHitomiCandidateIdentityOrNull()
        }
        val originalExtension = parsedLocations
            .firstOrNull { candidate -> candidate.format == HitomiMediaFormat.ORIGINAL }
            ?.extension
            ?: failed.extension
        return HitomiMediaRecoveryIdentity(
            hash = failed.hash,
            originalExtension = originalExtension,
            hasAvif = parsedLocations.any { candidate -> candidate.format == HitomiMediaFormat.AVIF },
            configurationVersion = failed.configurationVersion,
            failedFormat = failed.format,
        )
    }

    private fun String.toHitomiCandidateIdentityOrNull(): HitomiCandidateIdentity? {
        val parsed = runCatching { URI(this) }.getOrNull() ?: return null
        val host = parsed.host?.lowercase(Locale.ROOT) ?: return null
        val format = when {
            HITOMI_AVIF_HOST.matches(host) -> HitomiMediaFormat.AVIF
            HITOMI_WEBP_HOST.matches(host) -> HitomiMediaFormat.WEBP
            HITOMI_ORIGINAL_HOST.matches(host) -> HitomiMediaFormat.ORIGINAL
            else -> return null
        }
        val segments = parsed.path
            ?.split('/')
            ?.filter(String::isNotBlank)
            .orEmpty()
        val baseStart = if (format == HitomiMediaFormat.ORIGINAL) {
            if (segments.firstOrNull() != "images") return null
            1
        } else {
            0
        }
        if (segments.size - baseStart < 3) return null
        val fileName = segments.last()
        val hash = fileName.substringBeforeLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        if (!HITOMI_MEDIA_HASH.matches(hash) || !HITOMI_MEDIA_EXTENSION.matches(extension)) return null
        val pathNumberIndex = segments.lastIndex - 1
        if (segments[pathNumberIndex].toIntOrNull() == null) return null
        val baseSegments = segments.subList(baseStart, pathNumberIndex)
        if (baseSegments.isEmpty()) return null
        return HitomiCandidateIdentity(
            hash = hash,
            extension = extension,
            configurationVersion = baseSegments.joinToString("/"),
            format = format,
        )
    }

    private fun List<HitomiMediaCandidate>.preferFormat(
        format: HitomiMediaFormat,
    ): List<HitomiMediaCandidate> {
        return filter { candidate -> candidate.format == format } +
            filterNot { candidate -> candidate.format == format }
    }

    private fun JsonObject.parseTaxonomy(): List<PostTaxonomyTerm> {
        val gallery = this
        return buildList {
            gallery.arrayValue("tags")?.forEach { element ->
                val tag = element.asObjectOrNull() ?: return@forEach
                val value = tag.stringValue("tag") ?: return@forEach
                val namespace = when {
                    tag.truthy("female") -> HITOMI_FEMALE_NAMESPACE
                    tag.truthy("male") -> HITOMI_MALE_NAMESPACE
                    else -> HITOMI_TAG_NAMESPACE
                }
                add(PostTaxonomyTerm(value, SearchFacet.TAG, namespace))
            }
            gallery.addObjectTaxonomy(
                target = this,
                field = "artists",
                valueField = "artist",
                facet = SearchFacet.ARTIST,
                namespace = HITOMI_ARTIST_NAMESPACE,
            )
            gallery.addObjectTaxonomy(
                target = this,
                field = "characters",
                valueField = "character",
                facet = SearchFacet.CHARACTER,
                namespace = HITOMI_CHARACTER_NAMESPACE,
            )
            gallery.addObjectTaxonomy(
                target = this,
                field = "parodys",
                valueField = "parody",
                facet = SearchFacet.SERIES,
                namespace = HITOMI_SERIES_NAMESPACE,
            )
            gallery.addObjectTaxonomy(
                target = this,
                field = "groups",
                valueField = "group",
                facet = SearchFacet.GROUP,
                namespace = HITOMI_GROUP_NAMESPACE,
            )
            gallery.stringValue("type")?.let { value ->
                add(PostTaxonomyTerm(value, SearchFacet.TYPE, HITOMI_TYPE_NAMESPACE))
            }
            gallery.stringValue("language")?.let { value ->
                add(PostTaxonomyTerm(value, SearchFacet.LANGUAGE, HITOMI_LANGUAGE_NAMESPACE))
            }
        }.distinctBy { term -> term.termCacheKey() }
    }

    private fun JsonObject.addObjectTaxonomy(
        field: String,
        valueField: String,
        facet: SearchFacet,
        namespace: String,
        target: MutableList<PostTaxonomyTerm>,
    ) {
        arrayValue(field)?.forEach { element ->
            val value = element.asObjectOrNull()?.stringValue(valueField) ?: return@forEach
            target += PostTaxonomyTerm(value, facet, namespace)
        }
    }

    private fun compileQuery(query: Query): CompiledHitomiQuery {
        val includes = query.includeTerms.normalizedDistinctTerms()
        val excludes = query.excludeTerms.normalizedDistinctTerms()
        (includes + excludes).forEach(::validateHitomiTerm)
        val includedLanguages = includes
            .filter { term -> term.facet == SearchFacet.LANGUAGE }
            .map(SearchTerm::value)
            .distinct()
        if (includedLanguages.size > 1) {
            return CompiledHitomiQuery(isUnsatisfiable = true)
        }
        val language = includedLanguages.singleOrNull() ?: HITOMI_ALL_LANGUAGE
        val positiveTerms = includes.filterNot { term -> term.facet == SearchFacet.LANGUAGE }
        val positiveIndexes = positiveTerms.map { term ->
            CompiledIndex(
                key = term.termCacheKey(),
                term = term,
                newestUrl = term.nozomiUrl(HitomiNozomiSort.NEWEST, language),
                sortedUrl = term.nozomiUrl(query.sort.toHitomiNozomiSort(), language),
            )
        }.distinctBy(CompiledIndex::key)
        val exclusionIndexes = excludes.map { term ->
            val request = if (term.facet == SearchFacet.LANGUAGE) {
                HitomiNozomiRequest(
                    language = term.value,
                    sort = HitomiNozomiSort.NEWEST,
                )
            } else {
                term.toNozomiRequest(HitomiNozomiSort.NEWEST, language)
            }
            CompiledIndex(
                key = term.termCacheKey(),
                term = term,
                newestUrl = HitomiNozomi.urlFor(request),
                sortedUrl = HitomiNozomi.urlFor(request),
            )
        }.distinctBy { index -> index.newestUrl }
        val base = CompiledIndex(
            key = BASE_PRIMARY_KEY,
            term = null,
            newestUrl = HitomiNozomi.urlFor(HitomiNozomiRequest(language = language)),
            sortedUrl = HitomiNozomi.urlFor(
                HitomiNozomiRequest(
                    language = language,
                    sort = query.sort.toHitomiNozomiSort(),
                ),
            ),
        )
        return CompiledHitomiQuery(
            base = base,
            positives = positiveIndexes,
            exclusions = exclusionIndexes.map { index -> index.forNewest() },
        )
    }

    private fun validateHitomiTerm(term: SearchTerm) {
        val namespace = term.sourceNamespace ?: return
        val allowedNamespaces = when (term.facet) {
            SearchFacet.TAG -> setOf(
                HITOMI_TAG_NAMESPACE,
                HITOMI_FEMALE_NAMESPACE,
                HITOMI_MALE_NAMESPACE,
            )
            SearchFacet.ARTIST -> setOf(HITOMI_ARTIST_NAMESPACE)
            SearchFacet.CHARACTER -> setOf(HITOMI_CHARACTER_NAMESPACE)
            SearchFacet.SERIES -> setOf(HITOMI_SERIES_NAMESPACE)
            SearchFacet.GROUP -> setOf(HITOMI_GROUP_NAMESPACE)
            SearchFacet.TYPE -> setOf(HITOMI_TYPE_NAMESPACE)
            SearchFacet.LANGUAGE -> setOf(HITOMI_LANGUAGE_NAMESPACE)
        }
        if (namespace !in allowedNamespaces) {
            throw sourceParseFailure(
                "Hitomi does not support ${term.facet.name.lowercase()} terms in the $namespace namespace",
            )
        }
    }

    private fun SearchTerm.nozomiUrl(sort: HitomiNozomiSort, language: String): String {
        return HitomiNozomi.urlFor(toNozomiRequest(sort, language))
    }

    private fun SearchTerm.toNozomiRequest(
        sort: HitomiNozomiSort,
        language: String,
    ): HitomiNozomiRequest {
        val normalizedNamespace = sourceNamespace?.lowercase(Locale.ROOT)
        val (area, tag) = when (facet) {
            SearchFacet.TAG -> {
                val prefix = normalizedNamespace
                    ?.takeIf { value -> value == HITOMI_FEMALE_NAMESPACE || value == HITOMI_MALE_NAMESPACE }
                HITOMI_TAG_NAMESPACE to if (prefix == null) value else "$prefix:$value"
            }
            SearchFacet.ARTIST -> HITOMI_ARTIST_NAMESPACE to value
            SearchFacet.CHARACTER -> HITOMI_CHARACTER_NAMESPACE to value
            SearchFacet.SERIES -> HITOMI_SERIES_NAMESPACE to value
            SearchFacet.GROUP -> HITOMI_GROUP_NAMESPACE to value
            SearchFacet.TYPE -> HITOMI_TYPE_NAMESPACE to value
            SearchFacet.LANGUAGE -> "all" to "index"
        }
        return HitomiNozomiRequest(area = area, tag = tag, language = language, sort = sort)
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
        val json = gson.toJson(token).toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json)
    }

    private fun decodePageToken(raw: String): DecodedHitomiPageToken {
        val token = try {
            val json = Base64.getUrlDecoder().decode(raw).toString(Charsets.UTF_8)
            gson.fromJson(json, HitomiPageToken::class.java)
                ?: throw IllegalArgumentException("null token")
        } catch (error: RuntimeException) {
            throw sourceParseFailure("Hitomi page token was malformed", error)
        }
        if (
            token.version != PAGE_TOKEN_VERSION ||
            token.queryHash.isNullOrBlank() ||
            token.primaryKey.isNullOrBlank() ||
            token.primaryOffset == null ||
            token.randomSeed == null
        ) {
            throw sourceParseFailure("Hitomi page token used an unsupported shape")
        }
        return DecodedHitomiPageToken(
            queryHash = token.queryHash,
            primaryKey = token.primaryKey,
            primaryOffset = token.primaryOffset,
            randomSeed = token.randomSeed,
            randomSnapshotFingerprint = token.randomSnapshotFingerprint
                ?.takeIf(RANDOM_SNAPSHOT_FINGERPRINT::matches)
                ?: token.randomSnapshotFingerprint?.let {
                    throw sourceParseFailure("Hitomi page token contained an invalid random snapshot")
                },
        )
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

    private data class CompiledHitomiQuery(
        val base: CompiledIndex? = null,
        val positives: List<CompiledIndex> = emptyList(),
        val exclusions: List<CompiledIndex> = emptyList(),
        val isUnsatisfiable: Boolean = false,
    ) {
        fun primaryOptions(sort: SortMode): List<CompiledIndex> {
            if (positives.isEmpty()) return listOf(requireNotNull(base).forSort(sort))
            return positives.map { index -> index.forSort(sort) }
        }

        fun secondaryIncludes(primaryKey: String): List<CompiledIndex> {
            return positives.filterNot { index -> index.key == primaryKey }.map(CompiledIndex::forNewest)
        }
    }

    private data class CompiledIndex(
        val key: String,
        val term: SearchTerm?,
        val newestUrl: String,
        val sortedUrl: String,
        val url: String = sortedUrl,
    ) {
        fun forNewest(): CompiledIndex = copy(url = newestUrl)
        fun forSort(sort: SortMode): CompiledIndex {
            return if (sort == SortMode.NEWEST || sort == SortMode.RANDOM) forNewest() else copy(url = sortedUrl)
        }
    }

    private data class PrimaryRange(
        val ids: List<Int>,
        val exhausted: Boolean,
    )

    private data class RandomOrderCacheKey(
        val url: String,
        val seed: Long,
        val snapshotFingerprint: String,
    )

    private data class RandomOrder(
        val ids: IntArray,
        val snapshotFingerprint: String,
    )

    private data class NozomiSnapshot(
        val ids: IntArray,
        val fingerprint: String,
    )

    private data class HitomiPageToken(
        val version: Int?,
        val queryHash: String?,
        val primaryKey: String?,
        val primaryOffset: Long?,
        val randomSeed: Long?,
        val randomSnapshotFingerprint: String?,
    )

    private data class DecodedHitomiPageToken(
        val queryHash: String,
        val primaryKey: String,
        val primaryOffset: Long,
        val randomSeed: Long,
        val randomSnapshotFingerprint: String?,
    )

    private data class ParsedContentRange(
        val startByte: Long,
        val endByte: Long,
        val totalBytes: Long?,
    )

    private data class ParsedHitomiFile(
        val hash: String,
        val name: String,
        val hasAvif: Boolean,
        val width: Int?,
        val height: Int?,
    )

    private data class HitomiCandidateIdentity(
        val hash: String,
        val extension: String,
        val configurationVersion: String,
        val format: HitomiMediaFormat,
    )

    private data class HitomiMediaRecoveryIdentity(
        val hash: String,
        val originalExtension: String,
        val hasAvif: Boolean,
        val configurationVersion: String,
        val failedFormat: HitomiMediaFormat,
    )

    companion object {
        private const val DEFAULT_PAGE_SIZE = 25
        private const val MAX_PAGE_SIZE = 100
        private const val DEFAULT_HYDRATION_CONCURRENCY = 4
        private const val MAX_HYDRATION_CONCURRENCY = 8
        private const val HITOMI_AUTOCOMPLETE_LIMIT = 10
        private const val PRIMARY_SCAN_CHUNK_IDS = 64
        private const val MAX_PRIMARY_SCAN_IDS_PER_PAGE = 10_000
        private const val MAX_SECONDARY_MEMBERSHIP_IDS = 500_000
        private const val MAX_MEMBERSHIP_CACHE_ENTRIES = 4
        private const val MAX_NOZOMI_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val MAX_RANDOM_GALLERY_IDS = HitomiNozomi.MAX_GALLERY_IDS
        private const val MAX_RANDOM_ORDER_CACHE_ENTRIES = 2
        private const val PAGE_TOKEN_VERSION = 2
        private const val BASE_PRIMARY_KEY = "__all__"
        private const val HITOMI_ALL_LANGUAGE = "all"
        private const val HITOMI_ANIME_TYPE = "anime"
        private const val HITOMI_TAG_NAMESPACE = "tag"
        private const val HITOMI_FEMALE_NAMESPACE = "female"
        private const val HITOMI_MALE_NAMESPACE = "male"
        private const val HITOMI_ARTIST_NAMESPACE = "artist"
        private const val HITOMI_CHARACTER_NAMESPACE = "character"
        private const val HITOMI_SERIES_NAMESPACE = "series"
        private const val HITOMI_GROUP_NAMESPACE = "group"
        private const val HITOMI_TYPE_NAMESPACE = "type"
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
        private const val HITOMI_LANGUAGE_NAMESPACE = "language"

        private val HITOMI_AVIF_HOST = Regex("""a[12]\.gold-usergeneratedcontent\.net""")
        private val HITOMI_WEBP_HOST = Regex("""w[12]\.gold-usergeneratedcontent\.net""")
        private val HITOMI_ORIGINAL_HOST = Regex("""[12]\.gold-usergeneratedcontent\.net""")
        private val HITOMI_MEDIA_HASH = Regex("""[0-9a-f]{64}""")
        private val HITOMI_MEDIA_EXTENSION = Regex("""[a-z0-9]{1,10}""")
        private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
        private val RANDOM_SNAPSHOT_FINGERPRINT = Regex("[0-9a-f]{64}")
        private val WHITESPACE_PATTERN = Regex("\\s+")
        private val EMPTY_IMAGE_REF = ImageRef(url = null, localPath = null, mime = null)

        private fun deterministicSeed(queryHash: String): Long {
            val digest = MessageDigest.getInstance("SHA-256").digest(queryHash.toByteArray(Charsets.UTF_8))
            return digest.take(Long.SIZE_BYTES).fold(0L) { result, byte ->
                (result shl Byte.SIZE_BITS) or (byte.toLong() and 0xffL)
            }
        }

        private fun sha256Hex(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
        }

        private fun hitomiVideoUrl(filename: String): String {
            val encoded = URLEncoder.encode(filename, Charsets.UTF_8.name()).replace("+", "%20")
            return "https://streaming.gold-usergeneratedcontent.net/videos/$encoded"
        }

        private fun parseHitomiDate(date: String?, published: String?): Long? {
            date?.let { raw ->
                listOf("yyyy-MM-dd HH:mm:ssX", "yyyy-MM-dd HH:mm:ssXX", "yyyy-MM-dd HH:mm:ssXXX")
                    .firstNotNullOfOrNull { pattern ->
                        runCatching { OffsetDateTime.parse(raw, DateTimeFormatter.ofPattern(pattern)) }
                            .getOrNull()
                    }
                    ?.let { parsed -> return parsed.toInstant().toEpochMilli() }
            }
            return published?.let { raw ->
                runCatching { LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
                    .getOrNull()
            }
        }

        private fun bytesToIdCount(bytes: Long): Long {
            if (bytes < 0L || bytes % Int.SIZE_BYTES != 0L) {
                throw HitomiProtocolException("Hitomi Nozomi byte length was not ID-aligned")
            }
            return bytes / Int.SIZE_BYTES
        }

        private fun validateNozomiBody(body: ByteArray) {
            HitomiNozomi.decodeGalleryIds(body)
        }

        private fun SourceByteResponse.contentRange(): ParsedContentRange? {
            val raw = headers.entries
                .firstOrNull { (name, _) -> name.equals("Content-Range", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?: return null
            val match = CONTENT_RANGE_PATTERN.matchEntire(raw.trim()) ?: return null
            return ParsedContentRange(
                startByte = match.groupValues[1].toLong(),
                endByte = match.groupValues[2].toLong(),
                totalBytes = match.groupValues[3].takeUnless { value -> value == "*" }?.toLong(),
            )
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

        private fun SortMode.toHitomiNozomiSort(): HitomiNozomiSort = when (this) {
            SortMode.NEWEST, SortMode.RANDOM -> HitomiNozomiSort.NEWEST
            SortMode.POPULAR -> HitomiNozomiSort.POPULAR_MONTH
            SortMode.TOP -> HitomiNozomiSort.POPULAR_YEAR
        }

        private fun Iterable<SearchTerm>.normalizedDistinctTerms(): List<SearchTerm> {
            return mapNotNull { term ->
                val value = term.value.trim().lowercase(Locale.ROOT).replace(WHITESPACE_PATTERN, " ")
                if (value.isBlank()) null else term.copy(
                    value = value,
                    sourceNamespace = term.sourceNamespace?.trim()?.lowercase(Locale.ROOT),
                )
            }.distinctBy { term -> term.termCacheKey() }
        }

        private fun SearchTerm.termCacheKey(): String {
            val namespace = when {
                facet == SearchFacet.TAG && sourceNamespace == null -> HITOMI_TAG_NAMESPACE
                else -> sourceNamespace.orEmpty().lowercase(Locale.ROOT)
            }
            return "${facet.name}|$namespace|${value.trim().lowercase(Locale.ROOT)}"
        }

        private fun PostTaxonomyTerm.termCacheKey(): String {
            return toSearchTerm().termCacheKey()
        }

        private fun IntArray.containsId(id: Int): Boolean = binarySearch(id) >= 0

        private fun FacetedTagSuggestion.termCacheKey(): String {
            return toSearchTerm().termCacheKey()
        }

        private fun JsonObject.stringValue(name: String): String? {
            val element = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
            return runCatching { element.asString.trim() }.getOrNull()?.takeIf(String::isNotBlank)
        }

        private fun JsonObject.untrimmedStringValue(name: String): String? {
            val element = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
            return runCatching { element.asString }.getOrNull()
        }

        private fun JsonObject.intValue(name: String): Int? {
            val element = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
            return runCatching { element.asInt }.getOrNull()
        }

        private fun JsonObject.arrayValue(name: String): JsonArray? {
            return get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
        }

        private fun JsonElement.asObjectOrNull(): JsonObject? {
            return takeIf(JsonElement::isJsonObject)?.asJsonObject
        }

        private fun JsonObject.truthy(name: String): Boolean {
            val element = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return false
            return runCatching {
                when {
                    element.asJsonPrimitive.isBoolean -> element.asBoolean
                    element.asJsonPrimitive.isNumber -> element.asInt != 0
                    else -> element.asString.isNotBlank()
                }
            }.getOrDefault(false)
        }

        private fun JsonObject.toHitomiMediaFile(): ParsedHitomiFile? {
            val hash = stringValue("hash") ?: return null
            val name = stringValue("name") ?: return null
            val extension = name.substringAfterLast('.', missingDelimiterValue = "")
            if (!HITOMI_FILE_HASH.matches(hash.lowercase(Locale.ROOT)) || !HITOMI_FILE_EXTENSION.matches(extension)) {
                return null
            }
            return ParsedHitomiFile(
                hash = hash,
                name = name,
                hasAvif = intValue("hasavif") == 1,
                width = intValue("width")?.takeIf { value -> value > 0 },
                height = intValue("height")?.takeIf { value -> value > 0 },
            )
        }

        private val HITOMI_FILE_HASH = Regex("[0-9a-f]{64}")
        private val HITOMI_FILE_EXTENSION = Regex("[A-Za-z0-9]{1,10}")
    }
}

private class HitomiGalleryException(
    val reason: SourceFailureReason,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
