package com.theoriacodex.sources.hitomi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitomiNozomiTest {
    @Test
    fun `compiles newest and popularity Nozomi routes`() {
        assertEquals(
            "https://ltn.gold-usergeneratedcontent.net/n/index-all.nozomi",
            HitomiNozomi.urlFor(HitomiNozomiRequest()),
        )
        assertEquals(
            "https://ltn.gold-usergeneratedcontent.net/n/artist/najar-all.nozomi",
            HitomiNozomi.urlFor(HitomiNozomiRequest(area = "artist", tag = "najar")),
        )
        assertEquals(
            "https://ltn.gold-usergeneratedcontent.net/n/tag/female%3Ax-ray-all.nozomi",
            HitomiNozomi.urlFor(HitomiNozomiRequest(area = "tag", tag = "female:x-ray")),
        )
        assertEquals(
            "https://ltn.gold-usergeneratedcontent.net/n/popular/month-all.nozomi",
            HitomiNozomi.urlFor(HitomiNozomiRequest(sort = HitomiNozomiSort.POPULAR_MONTH)),
        )
        assertEquals(
            "https://ltn.gold-usergeneratedcontent.net/n/artist/popular/month/najar-all.nozomi",
            HitomiNozomi.urlFor(
                HitomiNozomiRequest(
                    area = "artist",
                    tag = "najar",
                    sort = HitomiNozomiSort.POPULAR_MONTH,
                ),
            ),
        )
    }

    @Test
    fun `builds aligned inclusive byte ranges for gallery IDs`() {
        assertEquals(
            "bytes=0-99",
            HitomiNozomi.byteRangeForIds(firstIdIndex = 0, idCount = 25).headerValue,
        )
        assertEquals(
            "bytes=100-199",
            HitomiNozomi.byteRangeForIds(firstIdIndex = 25, idCount = 25).headerValue,
        )
    }

    @Test
    fun `decodes captured big endian artist gallery IDs`() {
        val ids = HitomiNozomi.decodeGalleryIds(fixtureHex("artist-najar.nozomi.hex"))

        assertEquals(29, ids.size)
        assertEquals(listOf(4_042_375, 4_042_353, 4_041_204), ids.take(3))
        assertEquals(2_491_545, ids.last())
    }

    @Test
    fun `decode and dedupe remain primitive with deterministic sorted snapshots`() {
        val decoded: IntArray = HitomiNozomi.decodeGalleryIds(
            byteArrayOf(
                0, 0, 0, 3,
                0, 0, 0, 1,
                0, 0, 0, 3,
                0, 0, 0, 2,
            ),
        )

        assertArrayEquals(intArrayOf(3, 1, 3, 2), decoded)
        assertArrayEquals(intArrayOf(1, 2, 3), decoded.sortedDistinctCopy())
        assertArrayEquals(intArrayOf(3, 1, 3, 2), decoded)
    }

    @Test
    fun `allocation model removes boxed decode and full permutation copy costs`() {
        val model = HitomiIdAllocationModel.forIdCount(HitomiNozomi.MAX_GALLERY_IDS)

        assertEquals(8_000_016L, model.primitiveIdBytes)
        assertEquals(48_000_032L, model.legacyBoxedDecodePeakBytes)
        assertEquals(40_000_016L, model.nozomiPeakBytesSaved)
        assertEquals(8_000_016L, model.commonInlineSourceBytes)
        assertEquals(16_000_032L, model.legacyInlineExtraBytes)
        assertEquals(8_000_032L, model.newInlineExtraBytes)
        assertEquals(16L, model.statelessPermutationBytes)
        assertEquals(8_000_000L, model.inlinePeakBytesSaved)
        assertEquals(8_000_016L, model.legacyPerSeedRetainedShuffleBytes)
        assertEquals(0L, model.newPerSeedRetainedShuffleBytes)
    }

    @Test
    fun `rejects truncated oversized and invalid ID payloads`() {
        assertProtocolFailure { HitomiNozomi.decodeGalleryIds(byteArrayOf(0, 0, 1)) }
        assertProtocolFailure {
            HitomiNozomi.decodeGalleryIds(
                bytes = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2),
                maxGalleryIds = 1,
            )
        }
        assertProtocolFailure { HitomiNozomi.decodeGalleryIds(byteArrayOf(0, 0, 0, 0)) }
    }

    @Test
    fun `rejects invalid or overflowing ranges`() {
        assertProtocolFailure { HitomiNozomi.byteRangeForIds(firstIdIndex = -1, idCount = 1) }
        assertProtocolFailure { HitomiNozomi.byteRangeForIds(firstIdIndex = 0, idCount = 0) }
        assertProtocolFailure { HitomiNozomi.byteRangeForIds(firstIdIndex = Long.MAX_VALUE, idCount = 1) }
    }

    @Test
    fun `rejects namespace values that are not Nozomi path areas`() {
        assertProtocolFailure { HitomiNozomi.urlFor(HitomiNozomiRequest(area = "female")) }
        assertProtocolFailure { HitomiNozomi.urlFor(HitomiNozomiRequest(area = "male")) }
        assertProtocolFailure { HitomiNozomi.urlFor(HitomiNozomiRequest(area = "language")) }
    }

    private fun fixtureHex(name: String): ByteArray {
        val hex = requireNotNull(javaClass.getResource("/hitomi/2026-07-10/$name")) {
            "Missing Hitomi fixture: $name"
        }.readText().filterNot(Char::isWhitespace)
        return hex.chunked(2).map { it.toInt(radix = 16).toByte() }.toByteArray()
    }

    private fun assertProtocolFailure(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected HitomiProtocolException, got $failure", failure is HitomiProtocolException)
    }
}
