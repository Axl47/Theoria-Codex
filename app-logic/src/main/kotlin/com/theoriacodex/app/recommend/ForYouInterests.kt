package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.RecommendationTagNormalization
import com.theoriacodex.domain.recommendation.TagAffinityBuilder
import kotlin.math.abs
import kotlin.random.Random

data class ForYouInterest(val tags: List<String>)

/**
 * Picks a source's For You interests from the tags of what the profile liked there ([documents]). Tags rank by the same
 * [TagAffinityInputs.affinity] score Trails rank chips by, so a tag on everything the profile watches, one for text on
 * the image, or one no more common in what it kept than in what it watched never leads a lane. Tags that keep appearing
 * together stay together as one interest.
 */
object ForYouInterests {
    /**
     * Up to [maxInterests] interests, strongest first, then the weakest remaining proven tag to explore. A tag the
     * source reports no posts for ([providerCounts] of 0) is left out. [random] varies each score by up to a fifth, so
     * which interests lead changes between Mixes; null keeps score order.
     */
    fun generate(
        source: SourceKey,
        documents: List<List<String>>,
        affinity: TagAffinityInputs,
        providerCounts: Map<String, Int?> = emptyMap(),
        random: Random? = null,
        maxInterests: Int = 4,
    ): List<ForYouInterest> {
        val normalized = documents.map { RecommendationTagNormalization.normalizeDistinct(source, it, 40) }
        val stats = TagAffinityBuilder.build(normalized, maxTagsPerDocument = 40)
        val counts = stats.tagDocumentCounts
        // Only tags the profile has shown it seeks: a lane costs a request and a chip, unlike a Trail's guess.
        val scores = counts.keys.filter { providerCounts[it] != 0 }
            .map { tag -> affinity.affinity(source, tag) }
            .filter { it.informative && it.score > 0.0 }
            .associate { it.tag to it.score + abs(it.score) * (random?.nextDouble(-JITTER, JITTER) ?: 0.0) }
        // Ties, such as every tag before any history, go to the tag liked most often.
        val anchors = scores.keys.sortedByDescending(counts::getValue).sortedByDescending(scores::getValue)
        val chosen = mutableListOf<ForYouInterest>()
        for (anchor in anchors) {
            if (chosen.any { anchor in it.tags }) continue
            val partner = anchors.firstOrNull { candidate ->
                candidate != anchor && chosen.none { candidate in it.tags } &&
                    stats.pairCount(anchor, candidate) >= 2 &&
                    stats.pairCount(anchor, candidate).toDouble() /
                    maxOf(counts.getValue(anchor), counts.getValue(candidate)) >= 0.6
            }
            chosen += ForYouInterest(listOfNotNull(anchor, partner))
            if (chosen.size == maxInterests.coerceIn(1, 40)) break
        }
        anchors.lastOrNull { tag -> chosen.none { tag in it.tags } }?.let { chosen += ForYouInterest(listOf(it)) }
        return chosen
    }
}

private const val JITTER = 0.2
