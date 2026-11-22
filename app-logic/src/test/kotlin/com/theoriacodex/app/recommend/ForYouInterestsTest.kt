package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ForYouInterestsTest {
    @Test fun `shuffle samples one stable weight per tag before sorting a large interest set`() {
        val documents = (1..300).map { listOf("interest_$it") }
        val random = CountingRandom()
        val generated = ForYouInterests.generate(SourceKey.GELBOORU, documents, TagAffinityInputs.Empty, random = random)
        assertEquals(documents.size, random.draws)
        assertEquals(generated, ForYouInterests.generate(SourceKey.GELBOORU, documents, TagAffinityInputs.Empty, random = CountingRandom()))
        assertEquals(5, generated.size)
    }

    @Test fun `separate interests stay together rather than creating unrelated pairs`() {
        val documents = List(4) { listOf("common", "night", "city") } +
            List(4) { listOf("common", "forest", "watercolor") }
        val interests = ForYouInterests.generate(SourceKey.GELBOORU, documents, likedOnly(documents))
        assertEquals(listOf(listOf("night", "city"), listOf("forest", "watercolor")), interests.map { it.tags })
    }

    @Test fun `lanes rank by lift and skip watched-everywhere, unlifted and image-text tags`() {
        val documents = List(15) { listOf("blush", "miku", "artist_name") } + List(3) { listOf("blush") } +
            List(6) { listOf("tree") } + List(6) { listOf("missing") }
        val affinity = TagAffinityInputs(
            interest = mapOf("blush" to 18.0, "miku" to 15.0, "artist name" to 15.0, "tree" to 6.0, "missing" to 6.0),
            watchedByTag = mapOf(SourceKey.GELBOORU to mapOf("blush" to 60L, "miku" to 5L, "artist name" to 2L, "tree" to 20L)),
            watchedBySource = mapOf(SourceKey.GELBOORU to 100L),
            keptByTag = mapOf(SourceKey.GELBOORU to mapOf("blush" to 18, "miku" to 15, "artist name" to 15, "tree" to 6, "missing" to 6)),
            keptBySource = mapOf(SourceKey.GELBOORU to 30),
        )
        val interests = ForYouInterests.generate(SourceKey.GELBOORU, documents, affinity, mapOf("missing" to 0))
        assertEquals(listOf(listOf("miku")), interests.map { it.tags })
    }

    @Test fun `sections and adjacent exploration are bounded and use source normalization`() {
        val interests = ForYouInterests.generate(SourceKey.GELBOORU,
            (1..12).map { listOf("Interest $it") }, TagAffinityInputs.Empty)
        assertEquals(5, interests.size)
        assertTrue(interests.all { it.tags.single().startsWith("interest_") })
        assertEquals(5, interests.flatMap { it.tags }.distinct().size)
        assertEquals(emptyList<ForYouInterest>(), ForYouInterests.generate(SourceKey.PIXIV, listOf(listOf("1000users入り")), TagAffinityInputs.Empty))
    }
}

/** What a profile whose only history is these Likes would have. */
private fun likedOnly(documents: List<List<String>>): TagAffinityInputs {
    val counts = documents.flatMap { tags -> tags.map(::normalizeMatchToken).distinct() }.groupingBy { it }.eachCount()
    return TagAffinityInputs(likedCounts = counts, likes = documents.size, interest = counts.mapValues { it.value.toDouble() })
}

private class CountingRandom : Random() {
    private val delegate = Random(42)
    var draws = 0
    override fun nextBits(bitCount: Int): Int = delegate.nextBits(bitCount)
    override fun nextDouble(from: Double, until: Double): Double {
        draws++
        return delegate.nextDouble(from, until)
    }
}
