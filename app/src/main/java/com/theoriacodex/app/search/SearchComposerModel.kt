package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup

/** How the composer adds the next tag; it stays selected until the user changes it. */
internal enum class SearchAddMode(val label: String) {
    REQUIRED("Must have"),
    ANY_OF("Any of"),
    EXCLUDED("Without"),
}

/** A required group that keeps its index in the draft, because group actions address that index. */
internal data class IndexedTermGroup(val index: Int, val group: SearchTermGroup)

internal data class ComposerSection(val title: String, val groups: List<IndexedTermGroup>)

private val composerFacetOrder = listOf(
    SearchFacet.ARTIST,
    SearchFacet.SERIES,
    SearchFacet.CHARACTER,
    SearchFacet.TAG,
    SearchFacet.GROUP,
    SearchFacet.TYPE,
    SearchFacet.LANGUAGE,
)

/**
 * Sections for the required tags. Sources that declare tag types get one section per type in use;
 * every other source, and Unified where types are not comparable, gets a single flat section.
 */
internal fun composerSections(groups: List<SearchTermGroup>, typedSource: Boolean): List<ComposerSection> {
    val indexed = groups.mapIndexed(::IndexedTermGroup)
    if (indexed.isEmpty()) return emptyList()
    if (!typedSource) return listOf(ComposerSection("Required", indexed))
    return indexed
        .groupBy { (_, group) -> group.terms.first().let { it.facet to it.sourceNamespace?.lowercase() } }
        .entries
        .sortedWith(compareBy({ composerFacetOrder.indexOf(it.key.first) }, { it.key.second.orEmpty() }))
        .map { (type, members) ->
            ComposerSection(searchScopeLabel(FacetedSearchScope(type.first, type.second)), members)
        }
}

/** One autocomplete row, carrying the action that adds it under the current mode and group. */
internal data class ComposerSuggestion(val text: String, val meta: String, val add: SearchAction)

/**
 * Applies the add mode to autocomplete results. A group's alternatives must share its tag type, so
 * "Any of" only offers suggestions the draft reducer would accept for [target].
 */
internal fun composerSuggestions(
    mode: SearchAddMode,
    target: IndexedTermGroup?,
    faceted: List<FacetedTagSuggestion>,
    plain: List<TagSuggestion>,
): List<ComposerSuggestion> {
    val alternativeTo = target?.takeIf { mode == SearchAddMode.ANY_OF }
    val anchor = alternativeTo?.group?.terms?.first()
    if (faceted.isNotEmpty()) {
        return faceted
            .filter { anchor == null || (it.facet == anchor.facet && it.sourceNamespace == anchor.sourceNamespace) }
            .map { suggestion ->
                val add = when {
                    alternativeTo != null ->
                        SearchAction.AddIncludeAlternative(alternativeTo.index, suggestion.toSearchTerm())
                    mode == SearchAddMode.EXCLUDED -> SearchAction.ExcludeSuggestion(suggestion)
                    else -> SearchAction.IncludeSuggestion(suggestion)
                }
                ComposerSuggestion(suggestion.text, facetedSuggestionMetaLabel(suggestion), add)
            }
    }
    if (anchor != null && (anchor.facet != SearchFacet.TAG || anchor.sourceNamespace != null)) return emptyList()
    return plain.map { suggestion ->
        val add = when {
            alternativeTo != null && anchor != null ->
                SearchAction.AddIncludeAlternative(alternativeTo.index, anchor.copy(value = suggestion.text))
            mode == SearchAddMode.EXCLUDED -> SearchAction.AddExcludeTerm(SearchTerm(suggestion.text))
            else -> SearchAction.AddIncludeTerm(SearchTerm(suggestion.text))
        }
        ComposerSuggestion(tagSuggestionDisplayText(suggestion), tagSuggestionMetaLabel(suggestion), add)
    }
}

/** Applies the add mode to typed text, or returns null when the input cannot be committed. */
internal fun composerTypedAction(mode: SearchAddMode, target: IndexedTermGroup?, input: String): SearchAction? {
    val typed = input.trim().takeIf(String::isNotBlank) ?: return null
    val alternativeTo = target?.takeIf { mode == SearchAddMode.ANY_OF }
    return when {
        alternativeTo != null ->
            SearchAction.AddIncludeAlternative(alternativeTo.index, alternativeTo.group.terms.first().copy(value = typed))
        mode == SearchAddMode.EXCLUDED && !typed.startsWith("-") -> SearchAction.CommitTagInput("-$typed")
        else -> SearchAction.CommitTagInput(input)
    }
}
