package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.query.QueryHash
import java.security.MessageDigest

internal data class HitomiResultPage(
    val ids: IntArray,
    val nextToken: String?,
)

internal class HitomiSearchPager(
    private val queryCompiler: HitomiQueryCompiler,
    private val nozomiReader: HitomiNozomiReader,
    private val randomSnapshotCache: HitomiRandomSnapshotCache,
    private val suggestionCounts: HitomiByteBudgetCache<String, Int>,
    private val pageSize: Int,
) {
    private val pageTokenCodec = HitomiPageTokenCodec()

    suspend fun page(query: Query, pageToken: String?): HitomiResultPage {
        val compiled = queryCompiler.compile(query)
        if (compiled.isUnsatisfiable) return EMPTY_PAGE

        val queryHash = QueryHash.from(query)
        val decodedToken = pageToken?.let(::decodePageToken)
        decodedToken?.requireContinues(queryHash, compiled.globalIndexVersion, query.sort)

        val primaryOptions = compiled.primaryOptions(query.sort)
        val primary = if (decodedToken == null) {
            choosePrimary(primaryOptions)
        } else {
            primaryOptions.firstOrNull { option -> option.key == decodedToken.primaryKey }
                ?: throw hitomiParseFailure("Hitomi page token references an invalid primary index")
        }
        val walk = Walk(
            queryHash = queryHash,
            primary = primary,
            seed = decodedToken?.randomSeed ?: deterministicSeed(queryHash),
            offset = decodedToken?.primaryOffset ?: 0L,
            globalIndexVersion = compiled.globalIndexVersion,
        )
        if (walk.offset < 0L) {
            throw hitomiParseFailure("Hitomi page token contained a negative primary offset")
        }

        val secondaryIncludes = compiled.secondaryIncludes(primary.key)
            .map { index -> nozomiReader.membershipFor(index) }
        if (secondaryIncludes.any(IntArray::isEmpty)) return EMPTY_PAGE
        val exclusions = compiled.exclusions
            .map { index -> nozomiReader.membershipFor(index) }

        return if (query.sort == SortMode.RANDOM) {
            randomPage(walk, decodedToken?.randomSnapshotFingerprint, secondaryIncludes, exclusions)
        } else {
            orderedPage(walk, secondaryIncludes, exclusions)
        }
    }

    private suspend fun orderedPage(
        walk: Walk,
        secondaryIncludes: List<IntArray>,
        exclusions: List<IntArray>,
    ): HitomiResultPage {
        val scanned = nozomiReader.scanMatchingIds(walk.primary, walk.offset, pageSize, secondaryIncludes, exclusions)
        val hasMore = !scanned.exhausted && scanned.scannedIds > 0
        return HitomiResultPage(
            ids = scanned.ids,
            nextToken = if (hasMore) nextToken(walk, scanned.nextOffset, randomSnapshotFingerprint = null) else null,
        )
    }

    private suspend fun randomPage(
        walk: Walk,
        expectedSnapshotFingerprint: String?,
        secondaryIncludes: List<IntArray>,
        exclusions: List<IntArray>,
    ): HitomiResultPage {
        val snapshot = randomSnapshotFor(walk.primary, expectedSnapshotFingerprint)
        val randomIds = snapshot.ids
        if (walk.offset > randomIds.size.toLong()) {
            throw hitomiParseFailure("Hitomi page token offset exceeded the random candidate list")
        }
        val permutation = randomIds.takeIf(IntArray::isNotEmpty)?.let { ids ->
            HitomiDeterministicPermutation(ids.size, walk.seed)
        }
        var offset = walk.offset
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
        return HitomiResultPage(
            ids = survivingIds.toIntArray(),
            nextToken = if (offset < randomIds.size.toLong()) nextToken(walk, offset, snapshot.fingerprint) else null,
        )
    }

    private suspend fun randomSnapshotFor(
        compiledIndex: CompiledIndex,
        expectedSnapshotFingerprint: String?,
    ): HitomiNozomiSnapshot {
        val snapshot = try {
            randomSnapshotCache.getOrLoad(compiledIndex.url, expectedSnapshotFingerprint) {
                nozomiReader.readCompleteNozomi(compiledIndex, MAX_RANDOM_GALLERY_IDS)
            }
        } catch (error: HitomiRandomSnapshotMismatchException) {
            throw hitomiParseFailure(RANDOM_SOURCE_CHANGED, error)
        }
        if (expectedSnapshotFingerprint != null && snapshot.fingerprint != expectedSnapshotFingerprint) {
            throw hitomiParseFailure(RANDOM_SOURCE_CHANGED)
        }
        return snapshot
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

    private fun nextToken(walk: Walk, offset: Long, randomSnapshotFingerprint: String?): String =
        pageTokenCodec.encode(
            HitomiPageToken(
                version = HITOMI_PAGE_TOKEN_VERSION,
                queryHash = walk.queryHash,
                primaryKey = walk.primary.key,
                primaryOffset = offset,
                randomSeed = walk.seed,
                randomSnapshotFingerprint = randomSnapshotFingerprint,
                randomPermutationVersion = randomSnapshotFingerprint?.let {
                    HitomiDeterministicPermutation.ALGORITHM_VERSION
                },
                globalIndexVersion = walk.globalIndexVersion,
            ),
        )

    private fun decodePageToken(raw: String): DecodedHitomiPageToken = try {
        pageTokenCodec.decode(raw)
    } catch (error: HitomiPageTokenException) {
        throw hitomiParseFailure(error.message ?: "Hitomi page token was invalid", error)
    }

    private data class Walk(
        val queryHash: String,
        val primary: CompiledIndex,
        val seed: Long,
        val offset: Long,
        val globalIndexVersion: String?,
    )

    private companion object {
        const val MAX_PRIMARY_SCAN_IDS_PER_PAGE = 10_000
        const val MAX_RANDOM_GALLERY_IDS = HitomiNozomi.MAX_GALLERY_IDS
        const val RANDOM_SOURCE_CHANGED = "Hitomi random source changed before the next page could be resumed"
        val EMPTY_PAGE = HitomiResultPage(IntArray(0), nextToken = null)

        fun deterministicSeed(queryHash: String): Long {
            val digest = MessageDigest.getInstance("SHA-256").digest(queryHash.toByteArray(Charsets.UTF_8))
            return digest.take(Long.SIZE_BYTES).fold(0L) { result, byte ->
                (result shl Byte.SIZE_BITS) or (byte.toLong() and 0xffL)
            }
        }
    }
}
