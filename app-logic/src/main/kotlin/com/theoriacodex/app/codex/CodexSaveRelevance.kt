package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.query.matchesIncludeTermGroups
import com.theoriacodex.domain.tags.normalizeMatchToken
import com.theoriacodex.domain.vision.PredictedTagIndex
import com.theoriacodex.app.recommend.evidence
import kotlin.math.ln

class CodexSaveRelevance(posts: List<Post>, searchQuery: Query? = null, private val predicted: PredictedTagIndex = PredictedTagIndex.Empty) {
    private val groupQueries = searchQuery?.effectiveIncludeTermGroups.orEmpty()
        .filter { group -> group.terms.any { it.value.isNotBlank() } }
        .map { group -> requireNotNull(searchQuery).copy(includeTermGroups = listOf(group)) }
    private val searchTags = groupQueries.flatMap { it.includeTags }.map(::normalizeMatchToken).toSet()
    private val tags: Map<String, Double> = buildMap {
        for (post in posts) post.matchingTags(predicted).forEach { (token, weight) -> merge(token, weight, ::maxOf) }
        searchTags.forEach(::remove)
    }
    val hasSignals: Boolean = tags.isNotEmpty() || groupQueries.isNotEmpty()

    fun score(savedPosts: List<Post>, rarity: TagRarity = TagRarity.Uniform): Double {
        val unique = savedPosts.distinctBy(Post::id)
        if (unique.isEmpty()) return 0.0
        var wholeMatches = 0.0
        var groupMatches = 0.0
        var tagMatches = 0.0
        for (post in unique) {
            val matchedGroups = groupQueries.count { post.matchesIncludeTermGroups(it) }
            if (groupQueries.isNotEmpty() && matchedGroups == groupQueries.size) wholeMatches++
            groupMatches += matchedGroups
            tagMatches += post.matchingTags(predicted).entries.sumOf { (token, weight) ->
                tags[token]?.let { weight * it * rarity.weight(token) } ?: 0.0
            }
        }
        val tagWeight = tags.entries.sumOf { (token, weight) -> weight * rarity.weight(token) }
        val tagDensity = if (tagWeight == 0.0) 0.0 else tagMatches / (unique.size + SMALL_CODEX_POSTS) / tagWeight
        if (groupQueries.isEmpty()) return tagDensity
        return WHOLE_QUERY_WEIGHT * wholeMatches / unique.size +
            SEARCH_GROUP_WEIGHT * groupMatches / unique.size / groupQueries.size +
            INCIDENTAL_TAG_WEIGHT * tagDensity
    }
}

private const val WHOLE_QUERY_WEIGHT = 0.75
private const val SEARCH_GROUP_WEIGHT = 0.20
private const val INCIDENTAL_TAG_WEIGHT = 0.05

private const val SMALL_CODEX_POSTS = 3

class TagRarity private constructor(private val posts: Int, private val postsByTag: Map<String, Int>) {
    fun weight(token: String): Double = if (posts == 0) 1.0 else ln((posts + 1.0) / ((postsByTag[token] ?: 0) + 1.0))

    companion object {
        val Uniform = TagRarity(0, emptyMap())

        fun of(library: Collection<Post>, predicted: PredictedTagIndex = PredictedTagIndex.Empty): TagRarity {
            val unique = library.distinctBy(Post::id)
            return TagRarity(unique.size, unique.flatMap { it.matchingTags(predicted).keys }.groupingBy { it }.eachCount())
        }
    }
}

private fun Post.matchingTags(predicted: PredictedTagIndex): Map<String, Double> {
    val provided = canonicalTags.ifEmpty { rawTags }.map(::normalizeMatchToken).filter(String::isNotBlank).associateWith { 1.0 }
    return predicted.evidence(id) + provided
}
