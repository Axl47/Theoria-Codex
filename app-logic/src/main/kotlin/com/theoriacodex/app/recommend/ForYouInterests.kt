package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.RecommendationTagNormalization
import com.theoriacodex.domain.recommendation.TagAffinityBuilder
import com.theoriacodex.domain.recommendation.sharesBooruVocabulary
import kotlin.math.abs
import kotlin.random.Random

data class ForYouInterest(val tags: List<String>)

/**
 * Picks a source's For You interests with the same [TagAffinityInputs.affinity] Trails rank chips by. Candidates are
 * the tags of what the profile liked ([documents], which may be other sources' Likes translated), kept on the source, or
 * chose. Once the source has the history, a candidate leads a lane only when [TagAffinity.proven] there, or, spelled
 * by [translate], on another source sharing its vocabulary, so a character followed on Pixiv seeds the boorus too.
 * Before that history every informative candidate may lead. Tags that keep appearing together on Likes stay together
 * as one interest.
 */
object ForYouInterests {
    /**
     * Up to [maxInterests] interests, strongest first, then the best guess (a tag too rare in what the profile watches
     * to judge yet) to explore. Tags in [unavailable] (ones the source has no posts for) are never candidates. [random]
     * varies each score by up to a fifth, so which interests lead changes between Mixes; null keeps score order.
     */
    fun generate(
        source: SourceKey,
        documents: List<List<String>>,
        affinity: TagAffinityInputs,
        translate: (tag: String, into: SourceKey) -> String? = { tag, _ -> tag },
        unavailable: Set<String> = emptySet(),
        random: Random? = null,
        maxInterests: Int = 4,
    ): List<ForYouInterest> {
        val normalized = documents.map { RecommendationTagNormalization.normalizeDistinct(source, it, 40) }
        val stats = TagAffinityBuilder.build(normalized, maxTagsPerDocument = 40)
        val counts = stats.tagDocumentCounts
        val ownTags = RecommendationTagNormalization.normalizeDistinct(source, affinity.keptByTag[source].orEmpty().keys + affinity.explicit)
        val judged = affinity.hasHistory(source)
        val kin = affinity.keptBySource.keys.filter { other ->
            other != source && source.sharesBooruVocabulary && other.sharesBooruVocabulary && affinity.hasHistory(other)
        }
        val leads = mutableMapOf<String, Double>()
        val guesses = mutableMapOf<String, Double>()
        for (tag in (counts.keys + ownTags).distinct() - unavailable) {
            val here = affinity.affinity(source, tag)
            if (here.universal) continue
            val evidence = when {
                !judged -> here.takeIf(TagAffinity::informative)
                here.proven -> here
                else -> kin.mapNotNull { other -> translate(tag, other)?.let { affinity.affinity(other, it) } }
                    .filter(TagAffinity::proven).maxByOrNull(TagAffinity::score)
            }
            when {
                evidence != null -> leads[tag] = evidence.score + abs(evidence.score) * (random?.nextDouble(-JITTER, JITTER) ?: 0.0)
                here.informative -> guesses[tag] = here.score
            }
        }
        // Ties, such as every tag before any history, go to the tag liked most often.
        val anchors = leads.keys.sortedByDescending { counts[it] ?: 0 }.sortedByDescending(leads::getValue)
        val chosen = mutableListOf<ForYouInterest>()
        for (anchor in anchors) {
            if (chosen.size == maxInterests.coerceIn(1, 40)) break
            if (chosen.any { anchor in it.tags }) continue
            val partner = anchors.firstOrNull { candidate ->
                candidate != anchor && chosen.none { candidate in it.tags } &&
                    stats.pairCount(anchor, candidate) >= 2 &&
                    stats.pairCount(anchor, candidate).toDouble() /
                    maxOf(counts[anchor] ?: 0, counts[candidate] ?: 0) >= 0.6
            }
            chosen += ForYouInterest(listOfNotNull(anchor, partner))
        }
        guesses.maxByOrNull { it.value }?.let { chosen += ForYouInterest(listOf(it.key)) }
        return chosen
    }
}

private const val JITTER = 0.2
