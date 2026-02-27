package com.theoriacodex.domain.recommendation

import com.theoriacodex.domain.model.SourceKey
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouTagSetGeneratorTest {
    @Test
    fun `returns single tag for sparse history`() {
        val generated = ForYouTagSetGenerator.generate(
            source = SourceKey.GELBOORU,
            likedDocuments = listOf(
                listOf("cloud"),
                listOf("cloud", "sunset"),
                listOf("cloud"),
                listOf("sky"),
            ),
            fallbackCandidates = emptyList(),
            random = ZeroRandom,
        )

        assertEquals(1, generated.size)
        assertEquals("cloud", generated.first())
    }

    @Test
    fun `uses strong pair when cooccurrence is high`() {
        val generated = ForYouTagSetGenerator.generate(
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
            fallbackCandidates = emptyList(),
            random = ZeroRandom,
        )

        assertEquals(listOf("cloud", "sky"), generated)
    }

    @Test
    fun `falls back to trending candidates when likes are empty`() {
        val generated = ForYouTagSetGenerator.generate(
            source = SourceKey.PIXIV,
            likedDocuments = emptyList(),
            fallbackCandidates = listOf("sunset", "cloud"),
            random = ZeroRandom,
        )

        assertEquals(listOf("sunset"), generated)
    }

    @Test
    fun `drops pixiv users tags from training data`() {
        val generated = ForYouTagSetGenerator.generate(
            source = SourceKey.PIXIV,
            likedDocuments = listOf(
                listOf("100users入り", "landscape"),
                listOf("landscape"),
                listOf("landscape"),
            ),
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
