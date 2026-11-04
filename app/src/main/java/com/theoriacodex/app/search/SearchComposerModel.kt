package com.theoriacodex.app.search

import com.theoriacodex.app.post.PostTagSelection
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchDraftReducer
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.tags.TagChipState
import com.theoriacodex.app.tags.TagTapEffect
import com.theoriacodex.app.tags.chipState
import com.theoriacodex.app.tags.tagTapEffect
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey

private const val MAX_SAVED_SUGGESTIONS = 5

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

/** One autocomplete row, carrying the term it stands for and the action that adds it to the current target. */
internal data class ComposerSuggestion(
    val text: String,
    val meta: String,
    val add: SearchAction,
    val term: SearchTerm,
    val saved: Boolean = false,
)

/** A saved tag, with the source whose rules decide when two spellings are the same tag. */
internal data class SavedTag(val source: SourceKey, val value: String) {
    val term: SearchTerm get() = SearchTerm(value)
}

/** How a saved tag currently relates to the draft. */
internal fun SavedTag.state(draft: Query): TagChipState =
    PostTagSelection(source, draft.includeTerms, draft.excludeTerms).chipState(term)

/** While a group is the target, a saved tag not yet in the query is only usable if the group would take it. */
internal fun SavedTag.canTap(draft: Query, targetIndex: Int): Boolean = targetIndex < 0 ||
    state(draft) != TagChipState.NONE ||
    SearchDraftReducer.postTermForGroup(draft, source, targetIndex, term) != null

/** The edits one tap makes, following the same add, exclude, clear cycle as tags in post info. */
internal fun SavedTag.tapActions(draft: Query, targetIndex: Int): List<SearchAction> {
    val selection = PostTagSelection(source, draft.includeTerms, draft.excludeTerms)
    return when (val effect = tagTapEffect(term, selection, targetIndex.takeIf { it >= 0 })) {
        TagTapEffect.AddRequired -> listOf(SearchAction.AddIncludeTerm(term))
        is TagTapEffect.AddAlternative -> listOf(SearchAction.AddSavedTagAlternative(source, effect.groupIndex, term))
        is TagTapEffect.SwitchToExcluded ->
            listOf(SearchAction.AddExcludeTerm(effect.stored), SearchAction.RemoveIncludeTerm(effect.stored))
        is TagTapEffect.Clear -> listOf(SearchAction.RemoveExcludeTerm(effect.stored))
    }
}

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
                ComposerSuggestion(suggestion.text, facetedSuggestionMetaLabel(suggestion), add, suggestion.toSearchTerm())
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
        ComposerSuggestion(tagSuggestionDisplayText(suggestion), tagSuggestionMetaLabel(suggestion), add, SearchTerm(suggestion.text))
    }
    return ComposerSuggestions(rows, hiddenCount = 0)
}

/**
 * Puts saved tags matching the typed text ahead of the provider's rows. A saved tag the provider also
 * returned keeps that row, so its count stays. Tags already in the draft or unable to join the target
 * group are left out, and at most [MAX_SAVED_SUGGESTIONS] appear so provider results stay in view.
 */
internal fun ComposerSuggestions.withSavedTags(
    target: ComposerAddTarget,
    input: String,
    savedTags: List<SavedTag>,
    draft: Query,
): ComposerSuggestions {
    val typed = input.trim().removePrefix("-")
    if (typed.isBlank()) return this
    val targetIndex = (target as? ComposerAddTarget.Alternative)?.index ?: -1
    val matches = savedTags
        .filter { tag ->
            sourceTagKey(tag.source, tag.value).contains(sourceTagKey(tag.source, typed)) &&
                tag.state(draft) == TagChipState.NONE && tag.canTap(draft, targetIndex)
        }
        .sortedByDescending { tag -> sourceTagKey(tag.source, tag.value).startsWith(sourceTagKey(tag.source, typed)) }
        .take(MAX_SAVED_SUGGESTIONS)
    val merged = matches.map { tag -> tag to rows.firstOrNull { row -> row.isGeneralFormOf(tag) } }
    val savedRows = merged.map { (tag, row) ->
        row?.copy(saved = true) ?: ComposerSuggestion(tag.value, "", tag.addAction(target), tag.term, saved = true)
    }
    return copy(rows = savedRows + (rows - merged.mapNotNull { it.second }.toSet()))
}

private fun ComposerSuggestion.isGeneralFormOf(tag: SavedTag): Boolean =
    term.facet == SearchFacet.TAG &&
        (term.sourceNamespace == null || term.sourceNamespace.equals("tag", ignoreCase = true)) &&
        sourceTagKey(tag.source, term.value) == sourceTagKey(tag.source, tag.value)

private fun SavedTag.addAction(target: ComposerAddTarget): SearchAction = when (target) {
    is ComposerAddTarget.Alternative -> SearchAction.AddSavedTagAlternative(source, target.index, term)
    ComposerAddTarget.Excluded -> SearchAction.AddExcludeTerm(term)
    ComposerAddTarget.Required -> SearchAction.AddIncludeTerm(term)
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
