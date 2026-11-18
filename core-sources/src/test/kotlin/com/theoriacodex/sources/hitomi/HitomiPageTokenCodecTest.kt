package com.theoriacodex.sources.hitomi

import com.theoriacodex.data.storage.assertGsonParity
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class HitomiPageTokenCodecTest {
    private val codec = HitomiPageTokenCodec()

    // Tokens older builds issued, which a restored feed may still hand back: v4-full.txt is the Gson encoder's output
    // (HTML-escaped strings); v2-legacy.txt predates permutation versions and carries a retired key.
    @Test
    fun `tokens older builds issued decode the same`() {
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
        assertEquals(
            DecodedHitomiPageToken(
                queryHash = "legacy-query",
                primaryKey = "all",
                primaryOffset = 12L,
                randomSeed = 5L,
                randomSnapshotFingerprint = null,
                randomPermutationVersion = null,
                globalIndexVersion = null,
            ),
            codec.decode(golden("v2-legacy.txt")),
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
            globalIndexVersion = "3",
        )
        assertEquals(
            DecodedHitomiPageToken("q=1&x<y>'z'", "all", 0L, 7L, null, null, "3"),
            codec.decode(codec.encode(token)),
        )
    }

    @Test
    fun `the kotlinx serializer matches Gson`() {
        assertGsonParity(
            samples = listOf(
                HitomiPageToken(),
                HitomiPageToken(4, "q=1&x<y>'z'", "all", 1L, 2L, "a".repeat(64), 3, "4"),
            ),
            goldenJson = listOf("v4-full.txt", "v2-legacy.txt").map { name ->
                Base64.getUrlDecoder().decode(golden(name)).toString(Charsets.UTF_8)
            },
            json = hitomiPageTokenJson,
        )
    }

    private fun golden(name: String) =
        checkNotNull(javaClass.getResource("/golden/hitomi-token/$name")) { "missing golden $name" }.readText()
}
