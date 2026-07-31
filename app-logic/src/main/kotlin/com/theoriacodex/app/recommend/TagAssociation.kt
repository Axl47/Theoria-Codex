package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.RecommendationTagNormalization
import com.theoriacodex.domain.recommendation.TagAffinityBuilder
import com.theoriacodex.domain.recommendation.TagAffinityStats
import kotlin.math.pow

fun buildSourceTagAffinity(
    documentsBySource: Map<SourceKey, List<List<String>>>,
    maxTagsPerDocument: Int = MAX_AFFINITY_TAGS_PER_DOCUMENT,
): Map<SourceKey, TagAffinityStats> {
    return documentsBySource
        .mapNotNull { (source, documents) ->
            val normalized = documents
                .asSequence()
                .map { tags ->
                    RecommendationTagNormalization.normalizeDistinct(
                        source = source,
                        rawTags = tags,
                        limit = maxTagsPerDocument,
                    )
                }
                .filter { tags -> tags.isNotEmpty() }
                .toList()
            if (normalized.isEmpty()) return@mapNotNull null

            val stats = TagAffinityBuilder.build(
                documents = normalized,
                maxTagsPerDocument = maxTagsPerDocument,
            )
            if (stats.totalDocuments <= 0) {
                null
            } else {
                source to stats
            }
        }
        .toMap()
}

fun associatedDisplayTag(
    post: Post,
    seedTagsBySource: Map<SourceKey, List<String>>,
    affinityBySource: Map<SourceKey, TagAffinityStats>,
): String? {
    val recommendationTags = recommendationTagsFor(post)
    val fallback = recommendationTags.firstOrNull()
    val stats = affinityBySource[post.id.source] ?: return fallback
    if (stats.totalDocuments <= 0) return fallback

    val candidates = recommendationTags
        .mapNotNull { tag ->
            RecommendationTagNormalization.normalize(post.id.source, tag)?.let { normalized ->
                tag to normalized
            }
        }
        .distinctBy { (_, normalized) -> normalized }
    if (candidates.isEmpty()) return fallback

    val seedTags = seedTagsBySource[post.id.source]
        .orEmpty()
        .mapNotNull { tag -> RecommendationTagNormalization.normalize(post.id.source, tag) }
        .distinct()

    if (seedTags.isEmpty()) {
        return candidates.maxByOrNull { (_, normalized) ->
            (stats.tagDocumentCounts[normalized] ?: 0).toDouble()
        }?.first ?: fallback
    }

    return candidates.maxByOrNull { (_, normalizedCandidate) ->
        val candidateCount = stats.tagDocumentCounts[normalizedCandidate] ?: 0
        val frequencyScore = candidateCount.toDouble().pow(0.7) * TAG_FREQUENCY_WEIGHT
        val associationScore = seedTags.maxOfOrNull { normalizedSeed ->
            associationScore(
                stats = stats,
                normalizedSeed = normalizedSeed,
                normalizedCandidate = normalizedCandidate,
            )
        } ?: 0.0
        associationScore + frequencyScore
    }?.first ?: fallback
}

private fun associationScore(
    stats: TagAffinityStats,
    normalizedSeed: String,
    normalizedCandidate: String,
): Double {
    val seedCount = stats.tagDocumentCounts[normalizedSeed] ?: return 0.0
    val candidateCount = stats.tagDocumentCounts[normalizedCandidate] ?: return 0.0

    if (normalizedSeed == normalizedCandidate) {
        return DIRECT_SEED_MATCH_BONUS + (seedCount.toDouble() / (stats.totalDocuments + 1.0))
    }

    val pairCount = stats.pairCount(normalizedSeed, normalizedCandidate)
    if (pairCount <= 0) return 0.0

    val confidence = (pairCount + 1.0) / (seedCount + 2.0)
    val lift = ((pairCount + 1.0) * (stats.totalDocuments + 1.0)) /
        ((seedCount + 1.0) * (candidateCount + 1.0))
    return confidence * lift
}

private const val MAX_AFFINITY_TAGS_PER_DOCUMENT = 15
private const val DIRECT_SEED_MATCH_BONUS = 2.25
private const val TAG_FREQUENCY_WEIGHT = 0.15
