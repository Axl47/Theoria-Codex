package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.RecommendationTagNormalization
import com.theoriacodex.domain.recommendation.sharesBooruVocabulary
import com.theoriacodex.domain.tags.normalizeMatchToken
import kotlin.math.abs
import kotlin.random.Random

data class ForYouInterest(val tags: List<String>)

object ForYouInterests {
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
        val documentsByLead = HashMap<String, MutableList<Int>>()
        normalized.forEachIndexed { index, document ->
            document.forEach { tag -> if (tag in leads) documentsByLead.getOrPut(tag, ::mutableListOf) += index }
        }
        return ForYouInterestPicker(leads, guesses, documentsByLead.mapValues { it.value.toIntArray() })
    }

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

class ForYouInterestPicker internal constructor(
    private val leads: Map<String, TagAffinity>,
    private val guesses: Map<String, Double>,
    private val documentsByLead: Map<String, IntArray>,
) {
    fun pick(unavailable: Set<String> = emptySet(), random: Random? = null, maxInterests: Int = 4): List<ForYouInterest> {
        val scores = LinkedHashMap<String, Double>()
        for ((tag, evidence) in leads) {
            if (tag in unavailable) continue
            scores[tag] = evidence.score + abs(evidence.score) * (random?.nextDouble(-JITTER, JITTER) ?: 0.0)
        }
        val anchors = scores.keys.sortedByDescending(::count).sortedByDescending(scores::getValue)
        val chosen = mutableListOf<ForYouInterest>()
        for (anchor in anchors) {
            if (chosen.size == maxInterests.coerceIn(1, 40)) break
            if (chosen.any { anchor in it.tags }) continue
            val partner = anchors.firstOrNull { candidate ->
                candidate != anchor && chosen.none { candidate in it.tags } && togetherEnough(anchor, candidate)
            }
            chosen += ForYouInterest(listOfNotNull(anchor, partner))
        }
        guesses.filterKeys { it !in unavailable }.maxByOrNull { it.value }?.let { chosen += ForYouInterest(listOf(it.key)) }
        return chosen
    }

    private fun count(tag: String): Int = documentsByLead[tag]?.size ?: 0

    private fun togetherEnough(a: String, b: String): Boolean {
        val left = documentsByLead[a] ?: return false
        val right = documentsByLead[b] ?: return false
        val needed = maxOf(MIN_PAIR_DOCUMENTS.toDouble(), PAIR_SHARE * maxOf(left.size, right.size))
        if (minOf(left.size, right.size) < needed) return false
        var shared = 0
        var i = 0
        var j = 0
        while (i < left.size && j < right.size) {
            when {
                left[i] < right[j] -> i++
                left[i] > right[j] -> j++
                else -> { shared++; i++; j++ }
            }
        }
        return shared >= needed
    }
}

private const val JITTER = 0.2
private const val MIN_PAIR_DOCUMENTS = 2
private const val PAIR_SHARE = 0.6
