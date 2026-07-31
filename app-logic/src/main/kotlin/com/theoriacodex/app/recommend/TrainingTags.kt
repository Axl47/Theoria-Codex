package com.theoriacodex.app.recommend

import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey

fun trainingTagsFor(post: Post): List<String> {
    return recommendationTagsFor(post)
        .take(MAX_TRAINING_TAGS)
}

fun recommendationTagsFor(post: Post): List<String> {
    return recommendationTaxonomyFor(post)
        .asSequence()
        .map { term -> term.value.trim() }
        .filter { tag -> tag.isNotBlank() }
        .distinctBy { tag -> tag.lowercase() }
        .toList()
}

/**
 * Keeps each source's established recommendation vocabulary while using the typed taxonomy as
 * the authority for which values are general tags. Pixiv intentionally learns native raw tags
 * instead of counting native and translated canonical aliases as separate interests.
 */
fun recommendationTaxonomyFor(post: Post): List<PostTaxonomyTerm> {
    val preferredValues = if (post.id.source == SourceKey.PIXIV) {
        post.rawTags.ifEmpty { post.canonicalTags }
    } else {
        post.canonicalTags.ifEmpty { post.rawTags }
    }
    if (preferredValues.isEmpty()) return emptyList()

    val tagTermsByValue = post.taxonomy
        .asSequence()
        .filter { term -> term.facet == SearchFacet.TAG }
        .filter { term -> term.value.isNotBlank() }
        .groupBy { term -> term.value.taxonomyKey() }

    return preferredValues
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .flatMap { preferredValue ->
            tagTermsByValue[preferredValue.taxonomyKey()]
                .orEmpty()
                .asSequence()
                .map { term -> term.copy(value = preferredValue) }
        }
        .distinctBy { term ->
            Triple(
                term.facet,
                term.sourceNamespace?.trim()?.lowercase(),
                term.value.taxonomyKey(),
            )
        }
        .toList()
}

fun Query.recommendationIncludeTags(): List<String> {
    return includeTerms
        .asSequence()
        .filter { term -> term.facet == SearchFacet.TAG }
        .map { term -> term.value.trim() }
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .toList()
}

fun TagSuggestion.isRecommendationTagSuggestion(): Boolean {
    return when (type?.trim()?.lowercase()) {
        "artist", "character", "series", "parody", "group", "type", "category", "language" -> false
        else -> true
    }
}

private const val MAX_TRAINING_TAGS = 20

private fun String.taxonomyKey(): String = trim().lowercase()
