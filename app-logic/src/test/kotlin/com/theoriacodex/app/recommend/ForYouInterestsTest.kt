package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.CrossSourceTraining
import com.theoriacodex.domain.recommendation.LikedTagDocument
import com.theoriacodex.domain.recommendation.PixivTagDictionary
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
        assertEquals(4, generated.size)
    }

    @Test fun `separate interests stay together rather than creating unrelated pairs`() {
        val documents = List(4) { listOf("common", "night", "city") } +
            List(4) { listOf("common", "forest", "watercolor") }
        val interests = ForYouInterests.generate(SourceKey.GELBOORU, documents, likedOnly(documents))
        assertEquals(listOf(listOf("night", "city"), listOf("forest", "watercolor")), interests.map { it.tags })
    }

    @Test fun `lanes rank by lift, skip watched-everywhere, unlifted and image-text tags, and explore a guess`() {
        val documents = List(15) { listOf("blush", "miku", "artist_name") } + List(3) { listOf("blush") } +
            List(6) { listOf("tree") } + listOf(listOf("moth"))
        val kept = mapOf("blush" to 18, "miku" to 15, "artist name" to 15, "tree" to 6, "moth" to 1)
        val affinity = history(
            SourceKey.GELBOORU, kept, watched = mapOf("blush" to 60L, "miku" to 5L, "artist name" to 2L, "tree" to 20L),
        )
        val interests = ForYouInterests.generate(SourceKey.GELBOORU, documents, affinity)
        assertEquals(listOf(listOf("miku"), listOf("moth")), interests.map { it.tags })
    }

    @Test fun `a tag proven on Pixiv leads a lane on a booru that has history of its own`() {
        val affinity = history(SourceKey.GELBOORU, mapOf("cloud" to 3), watched = mapOf("cloud" to 3L))
            .merge(history(SourceKey.PIXIV, mapOf("初音ミク" to 15), watched = mapOf("初音ミク" to 5L)))
        val training = CrossSourceTraining(
            likes = List(15) { LikedTagDocument(SourceKey.PIXIV, listOf("初音ミク")) },
            dictionary = PixivTagDictionary.from(listOf("初音ミク" to "Hatsune Miku")),
        )
        val interests = ForYouInterests.generate(SourceKey.GELBOORU, training.documentsFor(SourceKey.GELBOORU), affinity,
            translate = { tag, into -> training.translate(tag, SourceKey.GELBOORU, into) })
        assertEquals(listOf("hatsune_miku"), interests.first().tags)
    }

    @Test fun `a saved tag leads a lane without any Like carrying it`() {
        val affinity = TagAffinityInputs(interest = mapOf("fox ears" to EXPLICIT_WEIGHT), explicit = setOf("fox ears"))
        assertEquals(listOf(listOf("fox_ears")), ForYouInterests.generate(SourceKey.GELBOORU, emptyList(), affinity).map { it.tags })
    }

    @Test fun `interests are bounded and use source normalization`() {
        val interests = ForYouInterests.generate(SourceKey.GELBOORU,
            (1..12).map { listOf("Interest $it") }, TagAffinityInputs.Empty)
        assertEquals(4, interests.size)
        assertTrue(interests.all { it.tags.single().startsWith("interest_") })
        assertEquals(4, interests.flatMap { it.tags }.distinct().size)
        assertEquals(emptyList<ForYouInterest>(), ForYouInterests.generate(SourceKey.PIXIV, listOf(listOf("1000users入り")), TagAffinityInputs.Empty))
    }
}

/** [source]'s history: 100 watched posts and 30 kept, carrying [kept] and [watched] of each tag. */
private fun history(source: SourceKey, kept: Map<String, Int>, watched: Map<String, Long>) = TagAffinityInputs(
    interest = kept.mapValues { it.value.toDouble() },
    watchedByTag = mapOf(source to watched), watchedBySource = mapOf(source to 100L),
    keptByTag = mapOf(source to kept), keptBySource = mapOf(source to 30),
)

private fun TagAffinityInputs.merge(other: TagAffinityInputs) = copy(
    interest = interest + other.interest,
    watchedByTag = watchedByTag + other.watchedByTag, watchedBySource = watchedBySource + other.watchedBySource,
    keptByTag = keptByTag + other.keptByTag, keptBySource = keptBySource + other.keptBySource,
)

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
