package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.RecommendationTagNormalization
import com.theoriacodex.domain.recommendation.TagAffinityBuilder
import com.theoriacodex.domain.recommendation.TagAffinityStats
import com.theoriacodex.domain.recommendation.sharesBooruVocabulary
import com.theoriacodex.domain.tags.normalizeMatchToken
import kotlin.math.abs
import kotlin.random.Random

data class ForYouInterest(val tags: List<String>)

/**
 * Picks a source's For You interests with the same [TagAffinityInputs.affinity] Trails rank chips by. Candidates are
 * the tags of what the profile liked ([documents], which may be other sources' Likes translated), kept on the source, or
 * chose. Once the source has the history, a candidate leads a lane only when [TagAffinity.proven] there, or, spelled
 * by [translate], on another source sharing its vocabulary, so a character followed on Pixiv seeds the boorus too.
 * Before that history every informative candidate may lead. [pictureCandidates] adds tags the picture tagger predicted
 * on kept posts (in the source's spelling, mapped to their Danbooru spelling); one leads only when
 * [pictureAffinity] proves it, and backs a provider candidate that nothing else proves. Tags that keep appearing together on Likes stay together
 * as one interest.
 */
object ForYouInterests {
    /**
     * Weighs every candidate once, so a caller that picks again around unavailable tags ([ForYouInterestPicker.pick])
     * repeats none of this work. Every tag of every document is a candidate; only the leads are paired.
     */
    fun prepare(
        source: SourceKey,
        documents: List<List<String>>,
        affinity: TagAffinityInputs,
        translate: (tag: String, into: SourceKey) -> String? = { tag, _ -> tag },
        pictureCandidates: Map<String, String> = emptyMap(),
    ): ForYouInterestPicker {
        val normalized = documents.map { RecommendationTagNormalization.normalizeDistinct(source, it) }
        val liked = normalized.flatten().distinct()
        val ownTags = RecommendationTagNormalization.normalizeDistinct(source, affinity.keptByTag[source].orEmpty().keys + affinity.explicit)
        val judged = affinity.hasHistory(source)
        val kin = affinity.keptBySource.keys.filter { other ->
            other != source && source.sharesBooruVocabulary && other.sharesBooruVocabulary && affinity.hasHistory(other)
        }
        val leads = LinkedHashMap<String, TagAffinity>()
        val guesses = LinkedHashMap<String, Double>()
        val provided = (liked + ownTags).mapTo(mutableSetOf(), ::normalizeMatchToken)
        val pictured = pictureCandidates.mapKeys { normalizeMatchToken(it.key) }
        // One candidate per matching key, in the spelling a Like gave it when one did.
        for (tag in (liked + ownTags + pictureCandidates.keys).distinctBy(::normalizeMatchToken)) {
            val token = normalizeMatchToken(tag)
            val here = affinity.affinity(source, tag)
            if (here.universal) continue
            val picture = pictured[token]?.let { affinity.pictureAffinity(source, it, tag) }?.takeIf(TagAffinity::proven)
            val evidence = when {
                token !in provided -> picture
                !judged -> here.takeIf(TagAffinity::informative) ?: picture
                here.proven -> here
                else -> kin.mapNotNull { other -> translate(tag, other)?.let { affinity.affinity(other, it) } }
                    .filter(TagAffinity::proven).maxByOrNull(TagAffinity::score) ?: picture
            }
            when {
                evidence != null -> leads[tag] = evidence
                here.informative && token in provided -> guesses[tag] = here.score
            }
        }
        // Only leads can pair, so only theirs are counted: pairs of every tag grow with the square of a post's tags.
        val stats = TagAffinityBuilder.build(normalized.map { document -> document.filter(leads::containsKey) }, Int.MAX_VALUE)
        return ForYouInterestPicker(leads, guesses, stats)
    }

    /** [prepare]s [source]'s candidates and picks once; see [ForYouInterestPicker.pick]. */
    fun generate(
        source: SourceKey,
        documents: List<List<String>>,
        affinity: TagAffinityInputs,
        translate: (tag: String, into: SourceKey) -> String? = { tag, _ -> tag },
        unavailable: Set<String> = emptySet(),
        random: Random? = null,
        maxInterests: Int = 4,
        pictureCandidates: Map<String, String> = emptyMap(),
    ): List<ForYouInterest> = prepare(source, documents, affinity, translate, pictureCandidates).pick(unavailable, random, maxInterests)
}

/** A source's weighed candidates, from [ForYouInterests.prepare]: [leads] and [guesses] in candidate order, [stats] over the leads. */
class ForYouInterestPicker internal constructor(
    private val leads: Map<String, TagAffinity>,
    private val guesses: Map<String, Double>,
    internal val stats: TagAffinityStats,
) {
    /**
     * Up to [maxInterests] interests, strongest first, then the best guess (a tag too rare in what the profile watches
     * to judge yet) to explore. Tags in [unavailable] (ones the source has no posts for) are never candidates. [random]
     * varies each score by up to a fifth, so which interests lead changes between Mixes; null keeps score order.
     */
    fun pick(unavailable: Set<String> = emptySet(), random: Random? = null, maxInterests: Int = 4): List<ForYouInterest> {
        val counts = stats.tagDocumentCounts
        val scores = LinkedHashMap<String, Double>()
        for ((tag, evidence) in leads) {
            if (tag in unavailable) continue
            scores[tag] = evidence.score + abs(evidence.score) * (random?.nextDouble(-JITTER, JITTER) ?: 0.0)
        }
        // Ties, such as every tag before any history, go to the tag liked most often.
        val anchors = scores.keys.sortedByDescending { counts[it] ?: 0 }.sortedByDescending(scores::getValue)
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
        guesses.filterKeys { it !in unavailable }.maxByOrNull { it.value }?.let { chosen += ForYouInterest(listOf(it.key)) }
        return chosen
    }
}

private const val JITTER = 0.2
