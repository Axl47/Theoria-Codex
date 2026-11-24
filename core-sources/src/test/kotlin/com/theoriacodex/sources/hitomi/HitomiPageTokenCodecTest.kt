package com.theoriacodex.sources.hitomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitomiPageTokenCodecTest {
    private val codec = HitomiPageTokenCodec()

    @Test
    fun `tokens the Gson encoder issued decode the same (ADR 0054)`() {
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

    @Test
    fun `a token of another format version is rejected (ADR 0043, 0054)`() {
        val current = HitomiPageToken(version = HITOMI_PAGE_TOKEN_VERSION, queryHash = "q", primaryKey = "all", primaryOffset = 0L, randomSeed = 7L)

        assertEquals("all", codec.decode(codec.encode(current)).primaryKey)
        for (version in listOf(HITOMI_PAGE_TOKEN_VERSION - 1, HITOMI_PAGE_TOKEN_VERSION + 1, null)) {
            val failure = runCatching { codec.decode(codec.encode(current.copy(version = version))) }.exceptionOrNull()
            assertTrue("version $version", failure is HitomiPageTokenException)
        }
    }

    private fun golden(name: String) =
        checkNotNull(javaClass.getResource("/golden/hitomi-token/$name")) { "missing golden $name" }.readText()
}
