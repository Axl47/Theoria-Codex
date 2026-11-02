package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.RecommendationTagNormalization
import com.theoriacodex.domain.recommendation.TagAffinityStats
import com.theoriacodex.domain.recommendation.TagAffinityBuilder
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

data class ForYouInterest(val tags: List<String>, val discovery: Boolean = false)

/** Keep co-occurring interests together; provider counts reduce the influence of broad tags. */
object ForYouInterests {
    fun generate(
        source: SourceKey,
        documents: List<List<String>>,
        providerCounts: Map<String, Int?> = emptyMap(),
        random: Random? = null,
        maxInterests: Int = 4,
    ): List<ForYouInterest> {
        val normalized = documents.map { RecommendationTagNormalization.normalizeDistinct(source, it, 40) }
        val stats = TagAffinityBuilder.build(normalized, maxTagsPerDocument = 40)
        val counts = stats.tagDocumentCounts
        val knownCounts = providerCounts.values.filterNotNull().filter { it > 0 }.sorted()
        val typicalCount = knownCounts.getOrNull((knownCounts.size - 1) / 2) ?: 0
        val weights = counts.keys.filter { providerCounts[it] != 0 }.associateWith { tag ->
            val prevalence = counts.getValue(tag).toDouble() / stats.totalDocuments
            val genericPenalty = if (prevalence > 0.85 && counts.size > 2) 0.25 else 1.0
            val specificity = 1.0 / (1.0 + ln(1.0 + (providerCounts[tag] ?: typicalCount).coerceAtLeast(0)))
            val variation = random?.nextDouble(0.8, 1.2) ?: 1.0
            sqrt(counts.getValue(tag).toDouble()) * genericPenalty * specificity * variation
        }
        // Sample each weight once: comparator keys must stay constant throughout the sort.
        val anchors = weights.keys.sortedByDescending { weights.getValue(it) }
        val chosen = mutableListOf<ForYouInterest>()
        for (anchor in anchors) {
            if (chosen.any { anchor in it.tags }) continue
            val partner = anchors.firstOrNull { candidate ->
                candidate != anchor && chosen.none { candidate in it.tags } &&
                    stats.pairCount(anchor, candidate) >= 2 &&
                    stats.pairCount(anchor, candidate).toDouble() /
                    maxOf(counts.getValue(anchor), counts.getValue(candidate)) >= 0.6
            }
            if (partner == null && chosen.isNotEmpty() && stats.isBroadTag(anchor)) continue
            chosen += ForYouInterest(listOfNotNull(anchor, partner))
            if (chosen.size == maxInterests.coerceIn(1, 40)) break
        }
        // A less familiar liked tag is still grounded in the user's interests.
        val adjacent = anchors.asReversed().firstOrNull { tag ->
            chosen.none { tag in it.tags } && !stats.isBroadTag(tag)
        }
        if (adjacent != null) chosen += ForYouInterest(listOf(adjacent), discovery = true)
        return chosen
    }
}

/** A ubiquitous tag alone adds little direction once more specific interests are represented. */
private fun TagAffinityStats.isBroadTag(tag: String): Boolean =
    totalDocuments >= 4 && tagDocumentCounts.getValue(tag).toDouble() / totalDocuments > 0.85
