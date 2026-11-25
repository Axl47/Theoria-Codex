package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.CrossSourceTraining
import com.theoriacodex.domain.recommendation.LikedTagDocument
import com.theoriacodex.domain.recommendation.PixivTagDictionary
import com.theoriacodex.domain.tags.normalizeMatchToken
import com.theoriacodex.domain.vision.PredictedTag
import com.theoriacodex.domain.vision.PredictedTagIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ForYouInterestsTest {
    @Test fun `shuffle samples one stable weight per tag before sorting a large interest set`() {
        val documents = (1..300).map { listOf("interest_$it") }
        val random = CountingRandom()
        val generated = ForYouInterests.prepare(SourceKey.GELBOORU, documents, TagAffinityInputs.Empty).pick(random = random)
        assertEquals(documents.size, random.draws)
        assertEquals(generated, ForYouInterests.prepare(SourceKey.GELBOORU, documents, TagAffinityInputs.Empty).pick(random = CountingRandom()))
        assertEquals(4, generated.size)
    }

    @Test fun `a tag far down a Like's alphabetical list still leads`() {
        val documents = List(3) { like -> (1..50).map { "filler_${like}_$it" } + "zorro" }
        val interests = ForYouInterests.prepare(SourceKey.GELBOORU, documents, likedOnly(documents)).pick()
        assertEquals(listOf("zorro"), interests.first().tags)
    }

    @Test fun `separate interests stay together rather than creating unrelated pairs (ADR 0071)`() {
        val documents = List(4) { listOf("common", "night", "city") } +
            List(4) { listOf("common", "forest", "watercolor") }
        val interests = ForYouInterests.prepare(SourceKey.GELBOORU, documents, likedOnly(documents)).pick()
        assertEquals(listOf(listOf("night", "city"), listOf("forest", "watercolor")), interests.map { it.tags })
    }

    @Test fun `lanes rank by lift, skip watched-everywhere, unlifted and image-text tags, and explore a guess (ADR 0071)`() {
        val documents = List(15) { listOf("blush", "miku", "artist_name") } + List(3) { listOf("blush") } +
            List(6) { listOf("tree") } + listOf(listOf("moth"), listOf("fern"))
        val kept = mapOf("blush" to 18, "miku" to 15, "artist name" to 15, "tree" to 6, "moth" to 1, "fern" to 1)
        val affinity = history(
            SourceKey.GELBOORU, kept, watched = mapOf("blush" to 60L, "miku" to 5L, "artist name" to 2L, "tree" to 20L, "fern" to 3L),
        )
        val interests = ForYouInterests.prepare(SourceKey.GELBOORU, documents, affinity).pick()
        assertEquals(listOf(listOf("miku"), listOf("moth")), interests.map { it.tags })
    }

    @Test fun `a tag proven on Pixiv leads a lane on a booru that has history of its own (ADR 0071)`() {
        val affinity = history(SourceKey.GELBOORU, mapOf("cloud" to 3), watched = mapOf("cloud" to 3L))
            .merge(history(SourceKey.PIXIV, mapOf("初音ミク" to 15), watched = mapOf("初音ミク" to 5L)))
        val training = CrossSourceTraining(
            likes = List(15) { LikedTagDocument(SourceKey.PIXIV, listOf("初音ミク")) },
            dictionary = PixivTagDictionary.from(listOf("初音ミク" to "Hatsune Miku")),
        )
        val interests = ForYouInterests.prepare(SourceKey.GELBOORU, training.documentsFor(SourceKey.GELBOORU), affinity,
            translate = { tag, into -> training.translate(tag, SourceKey.GELBOORU, into) }).pick()
        assertEquals(listOf("hatsune_miku"), interests.first().tags)
    }

    @Test fun `a tag on most of the source's watched posts leads no lane, even where another booru proves it (ADR 0087)`() {
        val affinity = history(SourceKey.GELBOORU, mapOf("blush" to 18, "miku" to 15), watched = mapOf("blush" to 60L, "miku" to 5L))
            .merge(history(SourceKey.DANBOORU, mapOf("blush" to 15), watched = mapOf("blush" to 5L)))
        val interests = ForYouInterests.prepare(SourceKey.GELBOORU, List(15) { listOf("blush", "miku") }, affinity).pick()
        assertEquals(listOf(listOf("miku")), interests.map { it.tags })
        val chosen = ForYouInterests.prepare(SourceKey.GELBOORU, List(15) { listOf("blush", "miku") }, affinity.copy(explicit = setOf("blush"))).pick()
        assertEquals(listOf(listOf("miku")), chosen.map { it.tags })
    }

    @Test fun `with equal scores the most liked tag leads (ADR 0071)`() {
        val documents = listOf(listOf("alpha"), listOf("beta"), listOf("beta"))
        val interests = ForYouInterests.prepare(SourceKey.GELBOORU, documents, TagAffinityInputs.Empty).pick()
        assertEquals(listOf(listOf("beta"), listOf("alpha")), interests.map { it.tags })
    }

    @Test fun `a higher score leads a tag that is liked more (ADR 0071)`() {
        val documents = List(2) { listOf("alpha") } + List(3) { listOf("beta") }
        val affinity = TagAffinityInputs(interest = mapOf("alpha" to 10.0, "beta" to 4.0))
        val interests = ForYouInterests.prepare(SourceKey.GELBOORU, documents, affinity).pick()
        assertEquals(listOf(listOf("alpha"), listOf("beta")), interests.map { it.tags })
    }

    @Test fun `tags the source cannot serve are never picked, as lead or as guess (ADR 0071)`() {
        val documents = List(15) { listOf("blush", "miku", "artist_name") } + List(3) { listOf("blush") } +
            List(6) { listOf("tree") } + listOf(listOf("moth"))
        val kept = mapOf("blush" to 18, "miku" to 15, "artist name" to 15, "tree" to 6, "moth" to 1)
        val affinity = history(
            SourceKey.GELBOORU, kept, watched = mapOf("blush" to 60L, "miku" to 5L, "artist name" to 2L, "tree" to 20L),
        )
        val picker = ForYouInterests.prepare(SourceKey.GELBOORU, documents, affinity)
        assertEquals(listOf(listOf("miku"), listOf("moth")), picker.pick().map { it.tags })

        val tags = picker.pick(unavailable = setOf("miku", "moth")).flatMap { it.tags }

        assertFalse(tags.toString(), "miku" in tags || "moth" in tags)
    }

    @Test fun `an unavailable tag is not paired in either (ADR 0071)`() {
        val documents = List(6) { listOf("x", "y") } + List(4) { listOf("z") }
        val picker = ForYouInterests.prepare(SourceKey.GELBOORU, documents, likedOnly(documents))
        assertEquals(listOf(listOf("x", "y"), listOf("z")), picker.pick().map { it.tags })
        assertEquals(listOf(listOf("x"), listOf("z")), picker.pick(unavailable = setOf("y")).map { it.tags })
    }

    @Test fun `tags pair only when they share most of the more common one's Likes and more than a single Like (ADR 0071)`() {
        fun picked(documents: List<List<String>>) =
            ForYouInterests.prepare(SourceKey.GELBOORU, documents, likedOnly(documents)).pick().map { it.tags }

        assertEquals(listOf(listOf("x", "y"), listOf("z")), picked(List(9) { listOf("x", "y") } + listOf(listOf("x"), listOf("y")) + List(4) { listOf("z") }))
        assertEquals(listOf(listOf("x"), listOf("y")), picked(List(2) { listOf("x", "y") } + List(8) { listOf("x") } + List(8) { listOf("y") }))
        assertEquals(listOf(listOf("x"), listOf("y")), picked(listOf(listOf("x", "y"))))
        assertEquals(
            listOf(listOf("x"), listOf("y"), listOf("z")),
            picked(List(4) { listOf("x", "y") } + List(6) { listOf("x") } + List(2) { listOf("y") } + List(3) { listOf("z") }),
        )
    }

    @Test fun `before any history, text on the image and tags about the file never lead (ADR 0071, 0072)`() {
        val documents = List(3) { listOf("artist_name", "3d", "cat") }
        val interests = ForYouInterests.prepare(SourceKey.GELBOORU, documents, likedOnly(documents)).pick()
        assertEquals(listOf(listOf("cat")), interests.map { it.tags })
    }

    @Test fun `a liked tag and its kept spelling are one candidate (ADR 0071)`() {
        val affinity = TagAffinityInputs(keptByTag = mapOf(SourceKey.PIXIV to mapOf("blue hair" to 1)), keptBySource = mapOf(SourceKey.PIXIV to 1))
        val interests = ForYouInterests.prepare(SourceKey.PIXIV, listOf(listOf("blue_hair")), affinity).pick()
        assertEquals(listOf(listOf("blue_hair")), interests.map { it.tags })
    }

    @Test fun `a saved tag leads a lane without any Like carrying it (ADR 0071)`() {
        val affinity = TagAffinityInputs(interest = mapOf("fox ears" to EXPLICIT_WEIGHT), explicit = setOf("fox ears"))
        assertEquals(listOf(listOf("fox_ears")), ForYouInterests.prepare(SourceKey.GELBOORU, emptyList(), affinity).pick().map { it.tags })
    }

    @Test fun `interests are bounded and use source normalization (ADR 0071)`() {
        val interests = ForYouInterests.prepare(SourceKey.GELBOORU,
            (1..12).map { listOf("Interest $it") }, TagAffinityInputs.Empty).pick()
        assertEquals(4, interests.size)
        assertTrue(interests.all { it.tags.single().startsWith("interest_") })
        assertEquals(4, interests.flatMap { it.tags }.distinct().size)
        assertEquals(emptyList<ForYouInterest>(), ForYouInterests.prepare(SourceKey.PIXIV, listOf(listOf("1000users入り")), TagAffinityInputs.Empty).pick())
    }
}

class ForYouPictureInterestsTest {
    private val source = SourceKey.GELBOORU

    @Test fun `a tag predicted on many kept pictures leads a lane, compared with what the profile watches (ADR 0071, 0087)`() {
        val affinity = history(source, mapOf("tree" to 3), watched = mapOf("tree" to 30L, "cat ears" to 2L))
            .copy(pictures = PictureEvidence(mapOf("cat ears" to 12.0, "solo" to 25.0), mapOf("cat ears" to 12, "solo" to 25), kept = 30, baseRates = mapOf("solo" to 0.6)))
        val interests = ForYouInterests.prepare(
            source, listOf(listOf("tree")), affinity, pictureCandidates = mapOf("cat_ears" to "cat ears", "solo" to "solo"),
        ).pick()
        assertEquals(listOf(listOf("cat_ears")), interests.map { it.tags })
    }

    @Test fun `before the source has watched enough, a predicted tag must beat its Danbooru share by a wide margin (ADR 0087)`() {
        val evidence = PictureEvidence(mapOf("cat ears" to 7.0), mapOf("cat ears" to 7), kept = 39, baseRates = mapOf("cat ears" to 0.15))
        val cold = TagAffinityInputs(pictures = evidence)
        fun proven(baseRate: Double) = cold.copy(pictures = evidence.copy(baseRates = mapOf("cat ears" to baseRate))).pictureAffinity(source, "cat ears", "cat_ears").proven
        assertFalse(proven(0.1875))
        assertFalse(proven(0.15))
        assertTrue(proven(0.02))
    }

    @Test fun `a predicted tag common on Danbooru is not proven by a low watched count (ADR 0087)`() {
        fun inputs(baseRate: Double) = TagAffinityInputs(
            watchedBySource = mapOf(source to 300L), watchedByTag = mapOf(source to mapOf("cat ears" to 3L)),
            pictures = PictureEvidence(mapOf("cat ears" to 6.0), mapOf("cat ears" to 6), kept = 30, baseRates = mapOf("cat ears" to baseRate)),
        )

        assertTrue(inputs(0.01).pictureAffinity(source, "cat ears", "cat_ears").proven)
        assertFalse(inputs(0.3).pictureAffinity(source, "cat ears", "cat_ears").proven)
    }

    @Test fun `too few analysed kept posts, or text on the image, prove nothing (ADR 0071)`() {
        val few = TagAffinityInputs(pictures = PictureEvidence(mapOf("cat ears" to 2.0), mapOf("cat ears" to 2), kept = 10, baseRates = mapOf("cat ears" to 0.01)))
        assertFalse(few.pictureAffinity(source, "cat ears", "cat_ears").proven)
        assertEquals(emptyList<ForYouInterest>(), ForYouInterests.prepare(source, emptyList(), few, pictureCandidates = mapOf("cat_ears" to "cat ears")).pick())
        val text = TagAffinityInputs(pictures = PictureEvidence(mapOf("signature" to 20.0), mapOf("signature" to 20), kept = 30, baseRates = mapOf("signature" to 0.01)))
        assertFalse(text.pictureAffinity(source, "signature", "signature").proven)
    }

    @Test fun `a tag about the file proves nothing by its picture, though a profile that chose it still gets it (ADR 0072, 0087)`() {
        val evidence = PictureEvidence(
            mapOf("3d" to 12.0, "cat ears" to 12.0), mapOf("3d" to 12, "cat ears" to 12), kept = 30, baseRates = mapOf("3d" to 0.01, "cat ears" to 0.01),
        )
        assertTrue(TagAffinityInputs(pictures = evidence).pictureAffinity(source, "cat ears", "cat_ears").proven)
        assertFalse(TagAffinityInputs(pictures = evidence).pictureAffinity(source, "3d", "3d").proven)
        val chosen = TagAffinityInputs(interest = mapOf("3d" to EXPLICIT_WEIGHT), explicit = setOf("3d"), pictures = evidence)
        assertTrue(chosen.affinity(source, "3d").proven)
    }

    @Test fun `picture evidence counts kept posts' confident predictions, weighted by score (ADR 0072, 0085)`() {
        val kept = PostId(SourceKey.NHENTAI, "kept")
        val keptToo = PostId(SourceKey.PIXIV, "kept-too")
        val index = PredictedTagIndex(
            byPost = mapOf(
                kept to listOf(PredictedTag("cat_ears", 0.8f, PredictedTag.Kind.GENERAL), PredictedTag("blush", 0.4f, PredictedTag.Kind.GENERAL)),
                keptToo to listOf(PredictedTag("cat_ears", 0.6f, PredictedTag.Kind.GENERAL)),
                PostId(SourceKey.DANBOORU, "candidate") to listOf(PredictedTag("cat_ears", 0.9f, PredictedTag.Kind.GENERAL)),
            ),
            baseRates = mapOf("cat ears" to 0.05),
        )
        val evidence = PictureEvidence.of(setOf(kept, keptToo, PostId(SourceKey.DANBOORU, "unanalysed")), index)
        assertEquals(2, evidence.kept)
        assertEquals(setOf("cat ears"), evidence.keptByTag.keys)
        assertEquals(1.4, evidence.keptByTag.getValue("cat ears"), 1e-6)
        assertEquals(mapOf("cat ears" to 2), evidence.postsByTag)
        assertEquals(mapOf("cat ears" to 0.05), evidence.baseRates)
    }

    @Test fun `text on the image and tags on half of Danbooru are never evidence (ADR 0072)`() {
        val post = PostId(SourceKey.PIXIV, "post")
        val tags = listOf("cat_ears", "signature", "solo", "blush", "tree").map { PredictedTag(it, 0.9f, PredictedTag.Kind.GENERAL) }
        val index = PredictedTagIndex(mapOf(post to tags), baseRates = mapOf("solo" to 0.6, "blush" to 0.5, "tree" to 0.49))
        assertEquals(setOf("cat ears", "tree"), index.evidence(post).keys)
    }

    @Test fun `a predicted tag on nearly every kept post is universal, however rare on Danbooru (ADR 0087)`() {
        val pictures = PictureEvidence(mapOf("looking at viewer" to 15.0), mapOf("looking at viewer" to 27), kept = 30, baseRates = mapOf("looking at viewer" to 0.3))
        val affinity = TagAffinityInputs(pictures = pictures).pictureAffinity(source, "looking at viewer", "looking_at_viewer")
        assertTrue(affinity.universal)
        assertFalse(affinity.proven)
    }
}

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
