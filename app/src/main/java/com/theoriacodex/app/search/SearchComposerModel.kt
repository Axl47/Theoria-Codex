package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup

/**
 * Where the composer's next tag goes. The query itself shows a chosen [Alternative] group, so only
 * required versus excluded remains as a setting.
 */
internal sealed interface ComposerAddTarget {
    data object Required : ComposerAddTarget
    data object Excluded : ComposerAddTarget

    /** An existing required group, keeping its draft index because group actions address that index. */
    data class Alternative(val index: Int, val group: SearchTermGroup) : ComposerAddTarget
}

/** Autocomplete rows for the target, and how many matches its group's tag type ruled out. */
internal data class ComposerSuggestions(val rows: List<ComposerSuggestion>, val hiddenCount: Int) {
    val isVisible: Boolean get() = rows.isNotEmpty() || hiddenCount > 0
}

/** One autocomplete row, carrying the action that adds it to the current target. */
internal data class ComposerSuggestion(val text: String, val meta: String, val add: SearchAction)

/**
 * Applies the add target to autocomplete results. A group's alternatives must share its tag type, so
 * an [ComposerAddTarget.Alternative] only offers suggestions the draft reducer would accept.
 */
internal fun composerSuggestions(
    target: ComposerAddTarget,
    faceted: List<FacetedTagSuggestion>,
    plain: List<TagSuggestion>,
): ComposerSuggestions {
    val alternative = target as? ComposerAddTarget.Alternative
    val anchor = alternative?.group?.terms?.first()
    if (faceted.isNotEmpty()) {
        val rows = faceted
            .filter { anchor == null || (it.facet == anchor.facet && it.sourceNamespace == anchor.sourceNamespace) }
            .map { suggestion ->
                val add = when {
                    alternative != null -> SearchAction.AddIncludeAlternative(alternative.index, suggestion.toSearchTerm())
                    target == ComposerAddTarget.Excluded -> SearchAction.ExcludeSuggestion(suggestion)
                    else -> SearchAction.IncludeSuggestion(suggestion)
                }
                ComposerSuggestion(suggestion.text, facetedSuggestionMetaLabel(suggestion), add)
            }
        return ComposerSuggestions(rows, faceted.size - rows.size)
    }
    if (anchor != null && (anchor.facet != SearchFacet.TAG || anchor.sourceNamespace != null)) {
        return ComposerSuggestions(emptyList(), plain.size)
    }
    val rows = plain.map { suggestion ->
        val add = when {
            alternative != null && anchor != null ->
                SearchAction.AddIncludeAlternative(alternative.index, anchor.copy(value = suggestion.text))
            target == ComposerAddTarget.Excluded -> SearchAction.AddExcludeTerm(SearchTerm(suggestion.text))
            else -> SearchAction.AddIncludeTerm(SearchTerm(suggestion.text))
        }
        ComposerSuggestion(tagSuggestionDisplayText(suggestion), tagSuggestionMetaLabel(suggestion), add)
    }
    return ComposerSuggestions(rows, hiddenCount = 0)
}

/** Explains matches hidden from an alternative group, naming the type that group accepts. */
internal fun composerHiddenSuggestionsNote(hiddenCount: Int, group: SearchTermGroup): String {
    val anchor = group.terms.first()
    val type = searchScopeLabel(FacetedSearchScope(anchor.facet, anchor.sourceNamespace))
    return "$hiddenCount ${if (hiddenCount == 1) "match" else "matches"} hidden: this group only takes $type."
}

/** Applies the add target to typed text, or returns null when the input cannot be committed. */
internal fun composerTypedAction(target: ComposerAddTarget, input: String): SearchAction? {
    val typed = input.trim().takeIf(String::isNotBlank) ?: return null
    return when (target) {
        is ComposerAddTarget.Alternative ->
            SearchAction.AddIncludeAlternative(target.index, target.group.terms.first().copy(value = typed))
        ComposerAddTarget.Excluded -> if (typed.startsWith("-")) SearchAction.CommitTagInput(input) else SearchAction.CommitTagInput("-$typed")
        ComposerAddTarget.Required -> SearchAction.CommitTagInput(input)
    }
}

/** The part of the draft the composer edits, and the source scope those tags belong to. */
internal data class ComposerTerms(
    val groups: List<SearchTermGroup>,
    val excluded: List<SearchTerm>,
    val sourceScope: SearchSourceScope,
)

/**
 * A removal that can still be undone. Undo restores [before], so it only stands while the draft is
 * [before] (the removal has not landed yet) or [after]; any other edit would be overwritten, and
 * tags restored into another source could be ones it cannot search.
 */
internal data class ComposerRemovalUndo(val message: String, val before: ComposerTerms, val after: ComposerTerms) {
    fun stillApplies(current: ComposerTerms): Boolean = current == before || current == after
}

/** Builds the undo for removing [removed] from the required groups, or the excluded list when [excluded]. */
internal fun composerRemovalUndo(before: ComposerTerms, removed: List<SearchTerm>, excluded: Boolean): ComposerRemovalUndo {
    val after = if (excluded) {
        before.copy(excluded = before.excluded - removed.toSet())
    } else {
        before.copy(
            groups = before.groups.mapNotNull { group ->
                (group.terms - removed.toSet()).takeIf(List<SearchTerm>::isNotEmpty)?.let(::SearchTermGroup)
            },
        )
    }
    return ComposerRemovalUndo("Removed ${removed.joinToString(" or ") { it.value }}", before, after)
}
