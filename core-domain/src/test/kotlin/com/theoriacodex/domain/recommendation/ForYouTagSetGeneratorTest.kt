package com.theoriacodex.domain.recommendation

import com.theoriacodex.domain.model.SourceKey
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouTagSetGeneratorTest {
    @Test
    fun `prepared samples preserve the established seeded sequence`() {
        val prepared = ForYouTagSetGenerator.prepare(
            SourceKey.GELBOORU,
            List(40) { index ->
                if (index % 3 == 0) listOf("city", "sunset") else listOf("cloud", "sky", "sunset")
            },
        )
        val random = Random(42)

        assertEquals(
            listOf(
                listOf("sunset"), listOf("sunset"), listOf("sunset", "cloud"), listOf("city", "sunset"),
                listOf("cloud"), listOf("sunset", "cloud"), listOf("sunset"), listOf("sunset", "cloud"),
                listOf("cloud", "sky"), listOf("cloud"), listOf("sky", "cloud"), listOf("sunset", "sky"),
                listOf("sunset", "cloud"), listOf("sky", "cloud"), listOf("cloud", "sky"), listOf("sky"),
            ),
            List(16) { prepared.sample(random = random) },
        )
    }

    @Test
    fun `training uses canonical keys and remains independent of later input changes`() {
        val tags = mutableListOf("  -Cloud   City  ", "cloud_city", " ")
        val prepared = ForYouTagSetGenerator.prepare(SourceKey.GELBOORU, listOf(tags))
        tags.clear()

        assertTrue(!prepared.needsFallback)
        assertEquals(List(16) { listOf("cloud_city") }, List(16) { prepared.sample(random = ZeroRandom) })
    }

    @Test
    fun `training with only unusable tags requests fallback without consuming random state`() {
        val prepared = ForYouTagSetGenerator.prepare(SourceKey.PIXIV, listOf(listOf("100users入り", " ")))
        val sampledRandom = Random(42)
        val expectedRandom = Random(42)
        val fallback = listOf("cloud", "sunset")

        assertTrue(prepared.needsFallback)
        assertEquals(emptyList<String>(), prepared.sample(random = sampledRandom))
        assertEquals(listOf(fallback[expectedRandom.nextInt(2)]), prepared.sample(fallback, sampledRandom))
        assertEquals(expectedRandom.nextLong(), sampledRandom.nextLong())
    }

    @Test
    fun `returns single tag for sparse history`() {
        val generated = ForYouTagSetGenerator.prepare(
            source = SourceKey.GELBOORU,
            likedDocuments = listOf(
                listOf("cloud"),
                listOf("cloud", "sunset"),
                listOf("cloud"),
                listOf("sky"),
            ),
        ).sample(
            fallbackCandidates = emptyList(),
            random = ZeroRandom,
        )

        assertEquals(1, generated.size)
        assertEquals("cloud", generated.first())
    }

    @Test
    fun `uses strong pair when cooccurrence is high`() {
        val generated = ForYouTagSetGenerator.prepare(
            source = SourceKey.GELBOORU,
            likedDocuments = listOf(
                listOf("cloud", "sky", "blue"),
                listOf("cloud", "sky"),
                listOf("cloud", "sky", "sunset"),
                listOf("cloud", "sky"),
                listOf("cloud", "sky"),
                listOf("cloud", "sky"),
                listOf("cloud", "sky"),
                listOf("cloud", "sky", "day"),
                listOf("cloud"),
                listOf("city"),
            ),
        ).sample(
            fallbackCandidates = emptyList(),
            random = ZeroRandom,
        )

        assertEquals(listOf("cloud", "sky"), generated)
    }

    @Test
    fun `falls back to trending candidates when likes are empty`() {
        val generated = ForYouTagSetGenerator.prepare(
            source = SourceKey.PIXIV,
            likedDocuments = emptyList(),
        ).sample(
            fallbackCandidates = listOf("sunset", "cloud"),
            random = ZeroRandom,
        )

        assertEquals(listOf("sunset"), generated)
    }

    @Test
    fun `fallback serving uses canonical recommendation keys`() {
        val generated = ForYouTagSetGenerator.prepare(
            source = SourceKey.GELBOORU,
            likedDocuments = emptyList(),
        ).sample(
            fallbackCandidates = listOf("  -Cloud   City  "),
            random = ZeroRandom,
        )

        assertEquals(listOf("cloud_city"), generated)
    }

    @Test
    fun `drops pixiv users tags from training data`() {
        val generated = ForYouTagSetGenerator.prepare(
            source = SourceKey.PIXIV,
            likedDocuments = listOf(
                listOf("100users入り", "landscape"),
                listOf("landscape"),
                listOf("landscape"),
            ),
        ).sample(
            fallbackCandidates = emptyList(),
            random = ZeroRandom,
        )

        assertTrue(generated.none { tag -> tag.contains("users入り") })
        assertEquals(listOf("landscape"), generated)
    }
}

private object ZeroRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}
