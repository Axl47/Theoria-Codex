package com.theoriacodex.sources.hitomi

import org.junit.Assert.assertEquals
import org.junit.Test

class HitomiPageTokenCodecTest {
    private val codec = HitomiPageTokenCodec()

    @Test
    fun `tokens the Gson encoder issued decode the same`() {
        assertEquals(
            DecodedHitomiPageToken(
                queryHash = "q=1&x<y>'z'",
                primaryKey = "tag/female:long_hair-all",
                primaryOffset = 120L,
                randomSeed = -42L,
                randomSnapshotFingerprint = "a".repeat(64),
                randomPermutationVersion = 2,
                globalIndexVersion = "1715000000",
            ),
            codec.decode(golden("v4-full.txt")),
        )
    }

    @Test
    fun `issued tokens round trip`() {
        val token = HitomiPageToken(
            version = HITOMI_PAGE_TOKEN_VERSION,
            queryHash = "q=1&x<y>'z'",
            primaryKey = "all",
            primaryOffset = 0L,
            randomSeed = 7L,
            randomSnapshotFingerprint = "b".repeat(64),
            randomPermutationVersion = 2,
            globalIndexVersion = "3",
        )
        assertEquals(
            DecodedHitomiPageToken("q=1&x<y>'z'", "all", 0L, 7L, "b".repeat(64), 2, "3"),
            codec.decode(codec.encode(token)),
        )
    }

    private fun golden(name: String) =
        checkNotNull(javaClass.getResource("/golden/hitomi-token/$name")) { "missing golden $name" }.readText()
}
