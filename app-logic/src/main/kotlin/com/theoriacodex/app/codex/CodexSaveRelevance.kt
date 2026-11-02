package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.query.matchesIncludeTermGroups
import com.theoriacodex.domain.tags.normalizeMatchToken

/** Match density removes collection-size bias; the applied search dominates incidental tags. */
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

    fun score(savedPosts: List<Post>): Double {
        val unique = savedPosts.distinctBy(Post::id)
        if (unique.isEmpty()) return 0.0
        var wholeMatches = 0.0
        var groupMatches = 0.0
        var tagMatches = 0.0
        for (post in unique) {
            val matchedGroups = groupQueries.count { post.matchesIncludeTermGroups(it) }
            if (groupQueries.isNotEmpty() && matchedGroups == groupQueries.size) wholeMatches++
            groupMatches += matchedGroups
            tagMatches += post.matchingTags().count { it in tags }
        }
        val tagDensity = if (tags.isEmpty()) 0.0 else tagMatches / unique.size / tags.size
        if (groupQueries.isEmpty()) return tagDensity
        return WHOLE_QUERY_WEIGHT * wholeMatches / unique.size +
            SEARCH_GROUP_WEIGHT * groupMatches / unique.size / groupQueries.size +
            INCIDENTAL_TAG_WEIGHT * tagDensity
    }
}

private const val WHOLE_QUERY_WEIGHT = 0.75
private const val SEARCH_GROUP_WEIGHT = 0.20
private const val INCIDENTAL_TAG_WEIGHT = 0.05

private fun Post.matchingTags(): Set<String> = canonicalTags.ifEmpty { rawTags }
    .map(::normalizeMatchToken).filter(String::isNotBlank).toSet()
