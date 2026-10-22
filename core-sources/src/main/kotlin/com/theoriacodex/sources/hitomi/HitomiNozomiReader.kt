package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpBodyTooLargeException
import com.theoriacodex.sources.http.SourceHttpClient
import java.io.IOException
import kotlin.math.max

/** Owns bounded binary-index transport, complete membership reads and byte-range validation. */
internal class HitomiNozomiReader(
    private val httpClient: SourceHttpClient,
    private val globalIndexCache: HitomiGlobalIndexCache,
    knownSizeCacheMaxBytes: Long,
    membershipCacheMaxBytes: Long,
) {
    private val knownNozomiSizes = HitomiByteBudgetCache<String, Long>(
        maxBytes = knownSizeCacheMaxBytes,
        weigh = { key, _ -> key.hitomiUtf8ByteWeight() + Long.SIZE_BYTES },
    )
    private val membershipCache = HitomiByteBudgetCache<String, IntArray>(
        maxBytes = membershipCacheMaxBytes,
        weigh = { key, ids -> key.hitomiUtf8ByteWeight() + ids.hitomiByteWeight() },
    )

    suspend fun knownSizeSnapshot() = knownNozomiSizes.snapshot()
    suspend fun membershipSnapshot() = membershipCache.snapshot()

    suspend fun scanMatchingIds(
        primary: CompiledIndex,
        initialOffset: Long,
        pageSize: Int,
        secondaryIncludes: List<IntArray>,
        exclusions: List<IntArray>,
    ): HitomiIndexScan {
        var primaryOffset = initialOffset
        val survivingIds = HitomiIntArrayBuilder(pageSize)
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
                index = primary,
                firstIdIndex = primaryOffset,
                idCount = requestedIds,
            )
            if (range.ids.isEmpty()) {
                exhausted = true
                break
            }

            val consumedFromRange = collectMatches(range.ids, survivingIds, pageSize, secondaryIncludes, exclusions)
            scannedIds += consumedFromRange
            primaryOffset += consumedFromRange
            exhausted = range.exhausted && consumedFromRange >= range.ids.size
        }

        return HitomiIndexScan(survivingIds.toIntArray(), primaryOffset, scannedIds, exhausted)
    }

    private fun collectMatches(
        ids: IntArray,
        survivingIds: HitomiIntArrayBuilder,
        pageSize: Int,
        secondaryIncludes: List<IntArray>,
        exclusions: List<IntArray>,
    ): Int {
        var consumedFromRange = 0
        for (galleryId in ids) {
            consumedFromRange += 1
            if (matchesHitomiMemberships(galleryId, secondaryIncludes, exclusions)) {
                survivingIds.add(galleryId)
            }
            if (survivingIds.size >= pageSize) break
        }

        return consumedFromRange
    }

    suspend fun readCompleteNozomi(
        index: CompiledIndex,
        maxGalleryIds: Int,
    ): HitomiNozomiSnapshot {
        val url = index.url
        (index.inlineIds ?: globalIndexCache.get(url))?.let { ordered ->
            if (ordered.size > maxGalleryIds) {
                throw sourceParseFailure("Hitomi global index exceeded the bounded gallery limit")
            }
            val uniqueIds = ordered.sortedDistinctCopy()
            return HitomiNozomiSnapshot(uniqueIds, uniqueIds.hitomiSha256Hex())
        }
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
        val ids = HitomiNozomi.decodeGalleryIds(body, maxGalleryIds).sortDistinctOwned()
        return HitomiNozomiSnapshot(ids = ids, fingerprint = ids.hitomiSha256Hex())
    }

    suspend fun nozomiSize(url: String): Long {
        globalIndexCache.get(url)?.let { return it.size.toLong() }
        knownNozomiSizes.get(url)?.let { return it }
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
        knownNozomiSizes.put(url, size)
        return size
    }

    suspend fun membershipFor(index: CompiledIndex): IntArray {
        val url = index.url
        membershipCache.get(url)?.let { return it }

        (index.inlineIds ?: globalIndexCache.get(url))?.let { ids ->
            val membership = ids.sortedDistinctCopy()
            membershipCache.put(url, membership)
            return membership
        }

        val response = requestNozomi(
            url = url,
            firstIdIndex = 0L,
            idCount = MAX_SECONDARY_MEMBERSHIP_IDS,
            maxBodyBytes = MAX_SECONDARY_MEMBERSHIP_IDS * Int.SIZE_BYTES,
        )
        val ids = when (response.statusCode) {
            404, 416 -> IntArray(0)
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
        val membership = ids.sortDistinctOwned()
        membershipCache.put(url, membership)
        return membership
    }

    suspend fun readPrimaryRange(
        index: CompiledIndex,
        firstIdIndex: Long,
        idCount: Int,
    ): PrimaryRange {
        val url = index.url
        (index.inlineIds ?: globalIndexCache.get(url))?.let { ids ->
            val from = firstIdIndex.coerceAtMost(ids.size.toLong()).toInt()
            val until = (firstIdIndex + idCount).coerceAtMost(ids.size.toLong()).toInt()
            return PrimaryRange(ids.primitiveSlice(from, until), exhausted = until >= ids.size)
        }
        val response = requestNozomi(url, firstIdIndex, idCount)
        return when (response.statusCode) {
            404, 416 -> PrimaryRange(IntArray(0), exhausted = true)
            200 -> {
                val allIds = HitomiNozomi.decodeGalleryIds(response.body)
                val from = firstIdIndex.coerceAtMost(allIds.size.toLong()).toInt()
                val until = (firstIdIndex + idCount)
                    .coerceAtMost(allIds.size.toLong())
                    .toInt()
                PrimaryRange(
                    ids = allIds.primitiveSlice(from, until),
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
                if (totalIds != null) knownNozomiSizes.put(url, totalIds)
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

    private fun sourceHttpFailure(step: String, statusCode: Int): SourceAdapterException = SourceAdapterException(
        reason = classifyHttpFailure(statusCode),
        message = "Hitomi $step request failed ($statusCode)",
    )

    private fun sourceParseFailure(message: String): SourceAdapterException = SourceAdapterException(
        reason = SourceFailureReason.PARSE,
        message = message,
    )

    private companion object {
        private const val PRIMARY_SCAN_CHUNK_IDS = 64
        private const val MAX_PRIMARY_SCAN_IDS_PER_PAGE = 10_000
        private const val MAX_SECONDARY_MEMBERSHIP_IDS = 500_000
        private const val MAX_NOZOMI_RESPONSE_BYTES = 8 * 1024 * 1024
        private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
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
    }
}

internal data class PrimaryRange(
    val ids: IntArray,
    val exhausted: Boolean,
)

private data class ParsedContentRange(
    val startByte: Long,
    val endByte: Long,
    val totalBytes: Long?,
)


internal data class HitomiIndexScan(
    val ids: IntArray,
    val nextOffset: Long,
    val scannedIds: Int,
    val exhausted: Boolean,
)

internal fun matchesHitomiMemberships(id: Int, includes: List<IntArray>, exclusions: List<IntArray>): Boolean =
    includes.all { it.binarySearch(id) >= 0 } && exclusions.none { it.binarySearch(id) >= 0 }
