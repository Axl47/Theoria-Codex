package com.theoriacodex.domain.recommendation

import com.theoriacodex.domain.model.SourceKey
import kotlin.math.pow
import kotlin.random.Random

object ForYouTagSetGenerator {
    fun generate(
        source: SourceKey,
        likedDocuments: List<List<String>>,
        fallbackCandidates: List<String>,
        random: Random = Random.Default,
    ): List<String> {
        val normalizedDocuments = likedDocuments
            .map { document ->
                document
                    .mapNotNull { tag -> normalizeTag(source, tag) }
                    .distinct()
                    .take(MAX_TAGS_PER_DOCUMENT)
            }
            .filter { it.isNotEmpty() }

        if (normalizedDocuments.isEmpty()) {
            return fallbackTag(source, fallbackCandidates, random)?.let(::listOf).orEmpty()
        }

        val stats = TagAffinityBuilder.build(
            documents = normalizedDocuments,
            maxTagsPerDocument = MAX_TAGS_PER_DOCUMENT,
        )
        if (stats.totalDocuments == 0) {
            return fallbackTag(source, fallbackCandidates, random)?.let(::listOf).orEmpty()
        }

        val minTagCount = minTagCount(stats.totalDocuments)
        val eligible = stats.tagDocumentCounts
            .filterValues { count -> count >= minTagCount }
            .ifEmpty { stats.tagDocumentCounts }
        if (eligible.isEmpty()) {
            return fallbackTag(source, fallbackCandidates, random)?.let(::listOf).orEmpty()
        }

        val anchor = weightedPick(
            random = random,
            weightedItems = eligible.map { (tag, count) ->
                WeightedTag(tag = tag, weight = count.toDouble().pow(0.7))
            },
        ) ?: return fallbackTag(source, fallbackCandidates, random)?.let(::listOf).orEmpty()

        if (stats.totalDocuments < MIN_LIKES_FOR_PAIRS) {
            return listOf(anchor)
        }

        if (random.nextDouble() > secondTagProbability(stats.totalDocuments)) {
            return listOf(anchor)
        }

        val anchorCount = stats.tagDocumentCounts[anchor] ?: return listOf(anchor)
        val minPairCount = minPairCount(stats.totalDocuments)
        val pairCandidates = eligible.keys
            .asSequence()
            .filter { candidate -> !candidate.equals(anchor, ignoreCase = true) }
            .mapNotNull { candidate ->
                val candidateCount = stats.tagDocumentCounts[candidate] ?: return@mapNotNull null
                val pairCount = stats.pairCount(anchor, candidate)
                if (pairCount < minPairCount) return@mapNotNull null

                val confidence = (pairCount + 1.0) / (anchorCount + 2.0)
                val lift = ((pairCount + 1.0) * (stats.totalDocuments + 1.0)) /
                    ((anchorCount + 1.0) * (candidateCount + 1.0))
                if (confidence < MIN_CONFIDENCE && lift < MIN_LIFT) return@mapNotNull null

                WeightedTag(
                    tag = candidate,
                    weight = maxOf(0.001, confidence * lift),
                )
            }
            .toList()

        val second = weightedPick(
            random = random,
            weightedItems = pairCandidates,
        ) ?: return listOf(anchor)

        return listOf(anchor, second)
    }

    private fun fallbackTag(
        source: SourceKey,
        candidates: List<String>,
        random: Random,
    ): String? {
        val normalized = candidates
            .mapNotNull { normalizeTag(source, it) }
            .distinct()
        if (normalized.isEmpty()) return null

        val topSlice = normalized.take(FALLBACK_WINDOW_SIZE)
        if (topSlice.isEmpty()) return normalized.firstOrNull()
        return topSlice[random.nextInt(topSlice.size)]
    }

    private fun normalizeTag(source: SourceKey, rawTag: String): String? {
        val cleaned = rawTag
            .trim()
            .removePrefix("-")
            .replace(WHITESPACE_REGEX, " ")
            .lowercase()
        if (cleaned.isBlank()) return null

        if (source == SourceKey.PIXIV && (cleaned.contains("users入り") || PIXIV_USERS_TAG_REGEX.matches(cleaned))) {
            return null
        }

        return when (source) {
            SourceKey.GELBOORU, SourceKey.AIBOORU, SourceKey.RULE34XXX -> cleaned.replace(' ', '_')
            SourceKey.PIXIV,
            SourceKey.NHENTAI,
            SourceKey.RULE34PAHEAL,
            SourceKey.RULE34VIDEO,
            SourceKey.RULE34GEN,
            -> cleaned
        }.takeIf { it.isNotBlank() }
    }

    private fun minTagCount(totalDocuments: Int): Int {
        return when {
            totalDocuments <= 3 -> 1
            totalDocuments <= 12 -> 2
            totalDocuments <= 30 -> 3
            else -> 4
        }
    }

    private fun minPairCount(totalDocuments: Int): Int {
        return when {
            totalDocuments <= 18 -> 2
            totalDocuments <= 45 -> 3
            else -> 4
        }
    }

    private fun secondTagProbability(totalDocuments: Int): Double {
        return when {
            totalDocuments <= 10 -> 0.45
            totalDocuments <= 30 -> 0.58
            else -> 0.68
        }
    }

    private fun weightedPick(
        random: Random,
        weightedItems: List<WeightedTag>,
    ): String? {
        if (weightedItems.isEmpty()) return null
        val total = weightedItems.sumOf { item -> item.weight.coerceAtLeast(0.0) }
        if (total <= 0.0) return weightedItems.firstOrNull()?.tag

        val target = random.nextDouble() * total
        var cumulative = 0.0
        weightedItems.forEach { item ->
            cumulative += item.weight.coerceAtLeast(0.0)
            if (target <= cumulative) {
                return item.tag
            }
        }
        return weightedItems.lastOrNull()?.tag
    }
}

private data class WeightedTag(
    val tag: String,
    val weight: Double,
)

private const val MIN_LIKES_FOR_PAIRS: Int = 8
private const val MAX_TAGS_PER_DOCUMENT: Int = 15
private const val FALLBACK_WINDOW_SIZE: Int = 5
private const val MIN_CONFIDENCE: Double = 0.25
private const val MIN_LIFT: Double = 2.0
private val WHITESPACE_REGEX = Regex("\\s+")
private val PIXIV_USERS_TAG_REGEX = Regex("\\d+users入り")
