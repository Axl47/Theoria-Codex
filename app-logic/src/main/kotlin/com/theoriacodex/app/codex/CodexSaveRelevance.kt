package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.query.matchesIncludeTermGroups
import com.theoriacodex.domain.tags.normalizeMatchToken
import kotlin.math.ln

/**
 * How well each Codex fits the incoming posts. The applied search dominates; without one, a Codex scores by the incoming
 * tags its posts carry, each weighted by [TagRarity] so a tag on half the library counts for little, over the Codex's
 * size plus a few posts, so a two-post Codex needs more than one lucky match to lead.
 */
class CodexSaveRelevance private constructor(rawTags: Iterable<String>, searchQuery: Query?) {
    constructor(posts: List<Post>, searchQuery: Query? = null) :
        this(posts.flatMap { it.matchingTags() }, searchQuery)
    private val groupQueries = searchQuery?.effectiveIncludeTermGroups.orEmpty()
        .filter { group -> group.terms.any { it.value.isNotBlank() } }
        .map { group -> requireNotNull(searchQuery).copy(includeTermGroups = listOf(group)) }
    private val searchTags = groupQueries.flatMap { it.includeTags }.map(::normalizeMatchToken).toSet()
    private val tags = rawTags.map(::normalizeMatchToken).filter(String::isNotBlank).toSet() - searchTags
    val hasSignals: Boolean = tags.isNotEmpty() || groupQueries.isNotEmpty()

    companion object {
        /** The same local tag lookup for interests that do not have full Post snapshots. */
        fun fromTags(tags: Iterable<String>): CodexSaveRelevance = CodexSaveRelevance(tags, null)
    }

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
            tagMatches += post.matchingTags().sumOf { if (it in tags) rarity.weight(it) else 0.0 }
        }
        val tagWeight = tags.sumOf(rarity::weight)
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

/** Posts added to every Codex's size when scoring tag matches; see [CodexSaveRelevance]. */
private const val SMALL_CODEX_POSTS = 3

/**
 * How much a tag match says: the rarer the tag across a saved library, the more (inverse document frequency). A tag on
 * every post weighs nothing. [Uniform] weighs every tag alike.
 */
class TagRarity private constructor(private val posts: Int, private val postsByTag: Map<String, Int>) {
    fun weight(token: String): Double = if (posts == 0) 1.0 else ln((posts + 1.0) / ((postsByTag[token] ?: 0) + 1.0))

    companion object {
        val Uniform = TagRarity(0, emptyMap())

        /** Rarity across [library], counting each post once. */
        fun of(library: Collection<Post>): TagRarity {
            val unique = library.distinctBy(Post::id)
            return TagRarity(unique.size, unique.flatMap { it.matchingTags() }.groupingBy { it }.eachCount())
        }
    }
}

private fun Post.matchingTags(): Set<String> = canonicalTags.ifEmpty { rawTags }
    .map(::normalizeMatchToken).filter(String::isNotBlank).toSet()
