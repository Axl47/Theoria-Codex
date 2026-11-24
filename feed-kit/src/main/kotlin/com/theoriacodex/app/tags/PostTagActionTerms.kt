package com.theoriacodex.app.tags

import com.theoriacodex.app.search.searchScopeLabel
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.GENERAL_TAG_NAMESPACE
import com.theoriacodex.domain.model.SearchTerm

internal data class PostActionTermGroup(
    val label: String,
    val terms: List<SearchTerm>,
)

internal fun postActionSuppliedCounts(post: Post): Map<SearchTerm, Int> = buildMap {
    post.taxonomy.forEach { taxonomy ->
        val term = taxonomy.toSearchTerm().normalizedPostActionTermOrNull()
        val count = taxonomy.count?.takeIf { it >= 0 }
        if (term != null && count != null) put(term, count)
    }
}

internal fun Post.tagTranslations(): Map<String, String> =
    taxonomy.mapNotNull { term -> term.translation?.let { term.value to it } }.toMap()

internal fun postTagChipLabel(value: String, translation: String?): String =
    translation?.let { "$it · $value" } ?: value

fun postActionTerms(post: Post): List<SearchTerm> {
    val typedTerms = post.taxonomy
        .mapNotNull { taxonomyTerm -> taxonomyTerm.toSearchTerm().normalizedPostActionTermOrNull() }
        .distinct()
    if (typedTerms.isNotEmpty()) return typedTerms

    val legacyTags = post.canonicalTags
        .ifEmpty { post.rawTags }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    return legacyTags.map { value -> SearchTerm(value = value) }
}

internal fun postActionTermGroups(post: Post): List<PostActionTermGroup> {
    return postActionTerms(post)
        .groupBy(SearchTerm::postActionGroupKey)
        .entries
        .sortedWith(
            compareBy<Map.Entry<PostActionGroupKey, List<SearchTerm>>> { entry ->
                postTagFacetOrder.indexOf(entry.key.facet)
            }.thenBy { entry -> entry.key.namespaceOrder }
                .thenBy { entry -> entry.key.sourceNamespace.orEmpty() },
        )
        .map { (key, terms) ->
            PostActionTermGroup(
                label = searchScopeLabel(
                    FacetedSearchScope(
                        facet = key.facet,
                        sourceNamespace = key.sourceNamespace,
                    ),
                ),
                terms = terms,
            )
        }
}

internal fun generalPostActionTags(post: Post): List<String> {
    return postActionTerms(post)
        .filter(SearchTerm::isGeneralTag)
        .map(SearchTerm::value)
        .distinct()
}

private data class PostActionGroupKey(
    val facet: SearchFacet,
    val sourceNamespace: String?,
) {
    val namespaceOrder: Int
        get() = when (sourceNamespace?.lowercase()) {
            null, GENERAL_TAG_NAMESPACE -> 0
            "female" -> 1
            "male" -> 2
            else -> 3
        }
}

private fun SearchTerm.postActionGroupKey(): PostActionGroupKey {
    val groupNamespace = sourceNamespace
        ?.takeUnless { namespace -> facet == SearchFacet.TAG && namespace.equals(GENERAL_TAG_NAMESPACE, true) }
    return PostActionGroupKey(facet = facet, sourceNamespace = groupNamespace)
}

private fun SearchTerm.normalizedPostActionTermOrNull(): SearchTerm? {
    val normalizedValue = value.trim().takeIf(String::isNotBlank) ?: return null
    val normalizedNamespace = sourceNamespace?.trim()?.takeIf(String::isNotBlank)
    return copy(value = normalizedValue, sourceNamespace = normalizedNamespace)
}

val postTagFacetOrder = listOf(
    SearchFacet.ARTIST,
    SearchFacet.SERIES,
    SearchFacet.CHARACTER,
    SearchFacet.TAG,
    SearchFacet.GROUP,
    SearchFacet.TYPE,
    SearchFacet.LANGUAGE,
)
