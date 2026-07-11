package com.theoriacodex.domain.recommendation

import com.theoriacodex.domain.model.SourceKey

/**
 * Canonical normalization for tags that train and serve the For You recommendation model.
 *
 * Search matching and persisted favorite tags intentionally use separate contracts because
 * their compatibility requirements differ from recommendation affinity keys.
 */
object RecommendationTagNormalization {
    fun normalize(
        source: SourceKey,
        rawTag: String,
    ): String? {
        val cleaned = rawTag
            .trim()
            .removePrefix("-")
            .trim()
            .replace(RECOMMENDATION_TAG_WHITESPACE_REGEX, " ")
            .lowercase()
        if (cleaned.isBlank()) return null

        if (source == SourceKey.PIXIV && isPixivPopularityMarker(cleaned)) {
            return null
        }

        return when (source) {
            SourceKey.GELBOORU,
            SourceKey.AIBOORU,
            SourceKey.IWARA,
            SourceKey.RULE34XXX,
            -> cleaned.replace(' ', '_')

            SourceKey.PIXIV,
            SourceKey.NHENTAI,
            SourceKey.HITOMI,
            SourceKey.RULE34PAHEAL,
            SourceKey.RULE34VIDEO,
            SourceKey.RULE34GEN,
            -> cleaned
        }.takeIf(String::isNotBlank)
    }

    fun normalizeDistinct(
        source: SourceKey,
        rawTags: Iterable<String>,
        limit: Int = Int.MAX_VALUE,
    ): List<String> {
        if (limit <= 0) return emptyList()
        return rawTags
            .asSequence()
            .mapNotNull { rawTag -> normalize(source, rawTag) }
            .distinct()
            .take(limit)
            .toList()
    }

    private fun isPixivPopularityMarker(tag: String): Boolean {
        return tag.contains("users入り") || PIXIV_USERS_TAG_REGEX.matches(tag)
    }
}

private val RECOMMENDATION_TAG_WHITESPACE_REGEX = Regex("\\s+")
private val PIXIV_USERS_TAG_REGEX = Regex("\\d+users入り")
