package com.theoriacodex.app.search

import com.theoriacodex.app.post.PostTagSelection
import com.theoriacodex.app.search.omnibox.OmniboxEntry
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchDraftReducer
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.source.presentation
import com.theoriacodex.app.tags.TagChipState
import com.theoriacodex.app.tags.TagTapEffect
import com.theoriacodex.app.tags.tagTapEffect
import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeMatchToken
import com.theoriacodex.domain.tags.sourceTagKey

private const val MAX_SAVED_SUGGESTIONS = 5

internal sealed interface ComposerAddTarget {
    data object Required : ComposerAddTarget
    data object Excluded : ComposerAddTarget

    data class Alternative(val index: Int, val group: SearchTermGroup) : ComposerAddTarget
}

internal data class ComposerSuggestions(
    val rows: List<ComposerSuggestion>,
    val hiddenCount: Int,
    val omnibox: List<OmniboxEntry> = emptyList(),
) {
    val isVisible: Boolean get() = rows.isNotEmpty() || hiddenCount > 0 || omnibox.isNotEmpty()
}

internal data class ComposerSuggestion(
    val text: String,
    val meta: String,
    val add: SearchAction,
    val term: SearchTerm,
    val saved: Boolean = false,
    val searched: Boolean = false,
)

internal fun FavoriteTagEntry.canTap(draft: Query, targetIndex: Int): Boolean = targetIndex < 0 ||
    state(draft) != TagChipState.NONE ||
    terms.singleOrNull()?.let { SearchDraftReducer.postTermForGroup(draft, source, targetIndex, it) } != null

internal fun FavoriteTagEntry.tapActions(draft: Query, targetIndex: Int): List<SearchAction> {
    val term = terms.singleOrNull() ?: return when (val index = groupIndexIn(draft)) {
        -1 -> listOf(SearchAction.AddSavedTagGroup(terms))
        else -> listOf(SearchAction.RemoveIncludeGroup(index))
    }
    val selection = PostTagSelection(source, draft.includeTerms, draft.excludeTerms)
    return when (val effect = tagTapEffect(term, selection, targetIndex.takeIf { it >= 0 })) {
        TagTapEffect.AddRequired -> listOf(SearchAction.AddIncludeTerm(term))
        is TagTapEffect.AddAlternative -> listOf(SearchAction.AddSavedTagAlternative(source, effect.groupIndex, term))
        is TagTapEffect.SwitchToExcluded ->
            listOf(SearchAction.AddExcludeTerm(effect.stored), SearchAction.RemoveIncludeTerm(effect.stored))
        is TagTapEffect.Clear -> listOf(SearchAction.RemoveExcludeTerm(effect.stored))
    }
}

internal fun savedGroupTapLabel(label: String, state: TagChipState): String =
    if (state == TagChipState.NONE) "Include $label in Search" else "Remove $label from Search"

internal class ComposerSavedSearches(
    val entries: List<SavedSearchEntry> = emptyList(),
    private val currentQueryHash: String? = null,
    val onOpen: (SavedSearchEntry) -> Unit = {},
    val onSaveCurrent: ((String) -> Unit)? = null,
    val suggestedName: String = "",
    val onManage: () -> Unit = {},
    val update: ComposerSavedSearchUpdate? = null,
) {
    val canSaveCurrent: Boolean get() = onSaveCurrent != null || update != null

    fun isCurrent(entry: SavedSearchEntry): Boolean = currentQueryHash != null && entry.search.queryHash == currentQueryHash
}

internal class ComposerSavedSearchUpdate(
    val name: String,
    val conflict: String? = null,
    val onUpdate: (() -> Unit)? = null,
)

internal class ComposerSavedTags(
    val entries: List<FavoriteTagEntry>,
    val saveSource: SourceKey? = null,
    val onSave: (SourceKey, List<String>, String?) -> Unit = { _, _, _ -> },
    val onRemove: (SourceKey, List<String>) -> Unit = { _, _ -> },
    val onRename: (SourceKey, List<String>, String) -> Unit = { _, _, _ -> },
) {
    fun entryFor(terms: List<SearchTerm>): FavoriteTagEntry? = entries.firstOrNull { it.matches(terms) }

    fun canSave(terms: List<SearchTerm>): Boolean = terms.isNotEmpty() && terms.all(SearchTerm::isGeneralTag)
}

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
                val term = suggestion.toSearchTerm()
                ComposerSuggestion(suggestion.text, facetedSuggestionMetaLabel(suggestion), target.add(term), term)
            }
        return ComposerSuggestions(rows, faceted.size - rows.size)
    }
    if (anchor != null && (anchor.facet != SearchFacet.TAG || anchor.sourceNamespace != null)) {
        return ComposerSuggestions(emptyList(), plain.size)
    }
    val rows = plain.map { suggestion ->
        ComposerSuggestion(
            tagSuggestionDisplayText(suggestion),
            tagSuggestionMetaLabel(suggestion),
            target.add(SearchTerm(suggestion.text)),
            SearchTerm(suggestion.text),
        )
    }
    return ComposerSuggestions(rows, hiddenCount = 0)
}

internal fun composerSuggestions(
    target: ComposerAddTarget,
    state: SearchUiState,
    savedTags: List<FavoriteTagEntry>,
): ComposerSuggestions {
    val scope = typedInputScope(parseScopedInput(state.suggestions.input), state.query.selectedScope, state.query.supportedScopes)
    val inScope = { term: SearchTerm -> scope?.accepts(term) == true }
    val draft = state.query.draft
    return composerSuggestions(target, state.suggestions.facetedAutocomplete, state.suggestions.autocomplete)
        .withSearchedTags(target, state.suggestions.searchedTags.filter(inScope), draft)
        .withSavedTags(target, state.suggestions.input, savedTags.filter { it.terms.all(inScope) }, draft)
}

private fun ComposerAddTarget.add(term: SearchTerm, savedFrom: SourceKey? = null): SearchAction = when (this) {
    is ComposerAddTarget.Alternative -> savedFrom?.let { SearchAction.AddSavedTagAlternative(it, index, term) }
        ?: SearchAction.AddIncludeAlternative(index, group.terms.first().copy(value = term.value))
    ComposerAddTarget.Excluded -> SearchAction.AddExcludeTerm(term)
    ComposerAddTarget.Required -> SearchAction.AddIncludeTerm(term)
}

private fun ComposerAddTarget.takes(term: SearchTerm): Boolean =
    this !is ComposerAddTarget.Alternative || group.terms.first().tagType == term.tagType

private fun SearchTerm.isSameTagAs(other: SearchTerm): Boolean =
    tagType == other.tagType && normalizeMatchToken(value) == normalizeMatchToken(other.value)

internal fun ComposerSuggestions.withSearchedTags(
    target: ComposerAddTarget,
    searchedTags: List<SearchTerm>,
    draft: Query,
): ComposerSuggestions {
    val inDraft = draft.includeTerms + draft.excludeTerms
    val merged = searchedTags
        .filter { term -> target.takes(term) && inDraft.none(term::isSameTagAs) }
        .map { term -> term to rows.firstOrNull { row -> row.term.isSameTagAs(term) } }
    if (merged.isEmpty()) return this
    val searchedRows = merged.map { (term, row) ->
        row?.copy(searched = true)
            ?: ComposerSuggestion(term.value, searchTermTypeLabel(term).orEmpty(), target.add(term), term, searched = true)
    }
    return copy(rows = searchedRows + (rows - merged.mapNotNull { it.second }.toSet()))
}

internal fun ComposerSuggestions.withSavedTags(
    target: ComposerAddTarget,
    input: String,
    savedTags: List<FavoriteTagEntry>,
    draft: Query,
): ComposerSuggestions {
    val typed = parseScopedInput(input).value
    if (typed.isBlank()) return this
    val targetIndex = (target as? ComposerAddTarget.Alternative)?.index ?: -1
    val matches = savedTags
        .filter { tag ->
            tag.tags.size == 1 &&
                sourceTagKey(tag.source, tag.tags.single()).contains(sourceTagKey(tag.source, typed)) &&
                tag.state(draft) == TagChipState.NONE && tag.canTap(draft, targetIndex)
        }
        .sortedByDescending { tag -> sourceTagKey(tag.source, tag.tags.single()).startsWith(sourceTagKey(tag.source, typed)) }
        .take(MAX_SAVED_SUGGESTIONS)
    val merged = matches.map { tag -> tag to rows.firstOrNull { row -> row.isGeneralFormOf(tag) } }
    val savedRows = merged.map { (tag, row) ->
        val term = tag.terms.single()
        row?.copy(saved = true) ?: ComposerSuggestion(term.value, "", target.add(term, savedFrom = tag.source), term, saved = true)
    }
    return copy(rows = savedRows + (rows - merged.mapNotNull { it.second }.toSet()))
}

private fun ComposerSuggestion.isGeneralFormOf(tag: FavoriteTagEntry): Boolean =
    term.isGeneralTag && sourceTagKey(tag.source, term.value) == sourceTagKey(tag.source, tag.tags.single())

internal fun composerHiddenSuggestionsNote(hiddenCount: Int, group: SearchTermGroup): String {
    val anchor = group.terms.first()
    val type = searchScopeLabel(FacetedSearchScope(anchor.facet, anchor.sourceNamespace))
    return "$hiddenCount ${if (hiddenCount == 1) "match" else "matches"} hidden: this group only takes $type."
}

internal fun composerTypedAction(target: ComposerAddTarget, input: String): SearchAction? {
    val typed = trimTagSeparators(input).takeIf(String::isNotBlank) ?: return null
    return when (target) {
        is ComposerAddTarget.Alternative ->
            SearchAction.AddIncludeAlternative(target.index, target.group.terms.first().copy(value = typed))
        ComposerAddTarget.Excluded -> SearchAction.CommitTagInput(if (typed.startsWith("-")) typed else "-$typed")
        ComposerAddTarget.Required -> SearchAction.CommitTagInput(typed)
    }
}

internal fun trimTagSeparators(input: String): String {
    fun String.stripped() = trim { it.isWhitespace() || it == ',' || it == ';' }
    val trimmed = input.stripped()
    return if (trimmed.startsWith('-')) "-" + trimmed.drop(1).stripped() else trimmed
}

internal data class ComposerTerms(
    val groups: List<SearchTermGroup>,
    val excluded: List<SearchTerm>,
    val sourceScope: SearchSourceScope,
)

internal data class ComposerRemovalUndo(val message: String, val before: ComposerTerms, val after: ComposerTerms) {
    fun stillApplies(current: ComposerTerms): Boolean = current == before || current == after
}

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
