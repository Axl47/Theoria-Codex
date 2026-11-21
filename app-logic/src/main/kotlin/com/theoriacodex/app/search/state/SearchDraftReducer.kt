package com.theoriacodex.app.search.state

import com.theoriacodex.app.search.DateRangePreset
import com.theoriacodex.app.search.NhentaiLanguageFilter
import com.theoriacodex.app.search.ParsedScopedInput
import com.theoriacodex.app.search.UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE
import com.theoriacodex.app.search.UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE
import com.theoriacodex.app.search.UNSUPPORTED_SEARCH_SCOPE_MESSAGE
import com.theoriacodex.app.search.parseScopedInput
import com.theoriacodex.app.search.rangeEndingAt
import com.theoriacodex.app.search.resolveSupportedScope
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.normalizedForMode
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.SearchTermLimit
import com.theoriacodex.domain.tags.sourceAutocompletePrefix
import com.theoriacodex.domain.tags.sourceTagsMatch

data class SearchDraftReduction(
    val state: SearchUiState,
    val accepted: Boolean = true,
)

/** Pure route-input reducer. SearchViewModel is the only holder of the returned state. */
object SearchDraftReducer {
    /** Applies one draft edit; [nowEpochMs] anchors a date-range preset. A rejected edit returns its reduction's state. */
    fun edit(state: SearchUiState, edit: SearchAction.DraftEdit, nowEpochMs: Long): SearchUiState = when (edit) {
        is SearchAction.SelectSort -> mutateQuery(state) { it.copy(sort = edit.sort) }
        is SearchAction.SetDateRange -> mutateQuery(state) { it.copy(dateRange = edit.range) }
        is SearchAction.SetDateRangePreset -> mutateQuery(state) { it.copy(dateRange = edit.preset.rangeEndingAt(nowEpochMs)) }
        is SearchAction.AddIncludeTerm -> addTerm(state, edit.term, excluded = false)
        is SearchAction.AddExcludeTerm -> addTerm(state, edit.term, excluded = true)
        is SearchAction.RemoveIncludeTerm -> removeTerm(state, edit.term, excluded = false)
        is SearchAction.RemoveExcludeTerm -> removeTerm(state, edit.term, excluded = true)
        is SearchAction.AddIncludeAlternative -> addIncludeAlternative(state, edit.groupIndex, edit.term)
        is SearchAction.SplitIncludeAlternative -> splitIncludeAlternative(state, edit.groupIndex, edit.term)
        is SearchAction.RemoveIncludeGroup -> removeIncludeGroup(state, edit.groupIndex)
        is SearchAction.AddSavedTagGroup -> addIncludeGroup(state, edit.terms)
        is SearchAction.RestoreDraftTerms -> restoreTerms(state, edit.includeTermGroups, edit.excludeTerms)
        SearchAction.ClearDraft -> clearDraft(state)
        is SearchAction.AddPostIncludeTerm -> addPostTerm(state, edit.post, edit.term, excluded = false)
        is SearchAction.AddPostExcludeTerm -> addPostTerm(state, edit.post, edit.term, excluded = true)
        is SearchAction.AddPostIncludeAlternative ->
            addKnownIncludeAlternative(state, edit.post.id.source, edit.groupIndex, edit.term)
        is SearchAction.AddSavedTagAlternative -> addKnownIncludeAlternative(state, edit.source, edit.groupIndex, edit.term)
        is SearchAction.SetNhentaiLanguage -> setNhentaiLanguage(state, edit.filter)
        is SearchAction.SetNhentaiFullColor -> setNhentaiFullColor(state, edit.enabled)
    }.state

    fun selectMode(state: SearchUiState, mode: QueryMode): SearchDraftReduction {
        val hadSourceOwnedTerms = state.query.draft.hasSourceOwnedTerms()
        val resolvedMode = mode.takeIf { it.isAvailable(state.query.availableSources) } ?: QueryMode.Unified
        val restored = state.query.appliedByMode[modeKey(resolvedMode)] ?: emptySearchQuery(resolvedMode)
        val sanitized = restored.copy(mode = resolvedMode).normalizedForMode(resolvedMode)
        val validation = UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE.takeIf {
            resolvedMode == QueryMode.Unified &&
                (hadSourceOwnedTerms || sanitized.removedSourceOwnedTerms)
        }
        return SearchDraftReduction(state.switchDraft(sanitized.query, validationMessage = validation))
    }

    fun toggleTemporarySource(state: SearchUiState, source: SourceKey): SearchDraftReduction {
        val available = state.query.availableSources.toSet()
        if (source !in available) return SearchDraftReduction(state, accepted = false)
        val nextScope = when (val current = state.query.draftSourceScope) {
            SearchSourceScope.GlobalUnified -> {
                val selected = state.query.unifiedSources.intersect(available)
                val updated = if (source in selected) selected - source else selected + source
                if (updated.isEmpty()) return SearchDraftReduction(state, accepted = false)
                SearchSourceScope.fromSources(updated)
            }
            is SearchSourceScope.Single -> {
                if (current.source == source) return SearchDraftReduction(state, accepted = false)
                SearchSourceScope.fromSources(listOf(current.source, source))
            }
            is SearchSourceScope.Temporary -> SearchSourceScope.fromSources(
                if (source in current.sources) current.sources - source else current.sources + source,
            )
        }
        if (nextScope == state.query.draftSourceScope) {
            return SearchDraftReduction(state, accepted = false)
        }
        val sanitized = state.query.draft.normalizedForMode(nextScope.queryMode())
        return SearchDraftReduction(
            state.switchDraft(
                sanitized.query,
                nextScope,
                validationMessage = UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE.takeIf { sanitized.removedSourceOwnedTerms },
                // The typed input stays for the new sources, so its error stays until their suggestions answer.
                suggestions = state.suggestions.withoutMatches(),
            ),
        )
    }

    /**
     * Fits the draft and the applied search to the sources available now, and takes what the engine reports about
     * them. A picked suggestion scope the draft's source no longer offers falls back to All.
     */
    fun reconcileEnvironment(
        state: SearchUiState,
        availableSources: List<SourceKey>,
        enabledSources: Set<SourceKey>,
        sourceTermLimits: Map<SourceKey, SearchTermLimit>,
        scopesBySource: Map<SourceKey, List<FacetedSearchScope>>,
        exactTagSources: Map<SourceKey, String>,
    ): SearchUiState {
        val available = availableSources.toSet()
        val draftScope = state.query.draftSourceScope.reconciledWith(available)
        val appliedScope = state.query.appliedSourceScope.reconciledWith(available)
        val query = state.query.copy(
            draft = state.query.draft.reconciledWith(draftScope),
            applied = state.query.applied.reconciledWith(appliedScope),
            draftSourceScope = draftScope,
            appliedSourceScope = appliedScope,
            availableSources = availableSources,
            unifiedSources = enabledSources.intersect(available),
            sourceTermLimits = sourceTermLimits,
            scopesBySource = scopesBySource,
            exactTagSources = exactTagSources,
        )
        return state.copy(query = query.copy(selectedScope = query.validSelectedScope(query.selectedScope, query.draft)))
    }

    fun mutateQuery(state: SearchUiState, transform: (Query) -> Query): SearchDraftReduction =
        SearchDraftReduction(state.copy(query = state.query.copy(draft = transform(state.query.draft))))

    fun addTerm(
        state: SearchUiState,
        term: SearchTerm,
        excluded: Boolean,
    ): SearchDraftReduction {
        val normalized = term.normalizedOrNull()
            ?: return SearchDraftReduction(state, accepted = false)
        if (state.query.draft.mode == QueryMode.Unified && !normalized.isPortableGeneralTag) {
            return SearchDraftReduction(
                state.copy(query = state.query.copy(validationMessage = UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE)),
                accepted = false,
            )
        }
        val current = if (excluded) state.query.draft.excludeTerms else state.query.draft.includeTerms
        if (normalized in current) return SearchDraftReduction(state, accepted = false)
        val reduced = mutateQuery(state) { query ->
            if (excluded) {
                query.copy(excludeTerms = query.excludeTerms + normalized)
            } else {
                query.withIncludeTermGroups(
                    query.effectiveIncludeTermGroups + SearchTermGroup.single(normalized),
                )
            }
        }
        return reduced.copy(
            state = reduced.state.copy(
                query = reduced.state.query.copy(validationMessage = null),
            ),
        )
    }

    fun removeTerm(state: SearchUiState, term: SearchTerm, excluded: Boolean): SearchDraftReduction {
        return mutateQuery(state) { query ->
            if (excluded) {
                query.copy(excludeTerms = query.excludeTerms.filterNot { it == term })
            } else {
                query.withIncludeTermGroups(
                    query.effectiveIncludeTermGroups.mapNotNull { group ->
                        group.terms.filterNot { it == term }
                            .takeIf(List<SearchTerm>::isNotEmpty)
                            ?.let(::SearchTermGroup)
                    }
                )
            }
        }
    }

    fun addIncludeAlternative(
        state: SearchUiState,
        groupIndex: Int,
        term: SearchTerm,
    ): SearchDraftReduction {
        if (
            term.isPortableGeneralTag &&
            state.query.draftExactTagSource != null &&
            !canCommitInput(state, term.value)
        ) {
            return SearchDraftReduction(
                state.copy(
                    suggestions = state.suggestions.copy(inputError = state.query.exactTagInputError()),
                ),
                accepted = false,
            )
        }
        val resolved = if (term.isPortableGeneralTag) {
            term.copy(value = parseScopedInput(resolveCommittedInput(state, term.value)).value)
        } else {
            term
        }
        val normalized = resolved.normalizedOrNull()
            ?: return SearchDraftReduction(state, accepted = false)
        val groups = state.query.draft.effectiveIncludeTermGroups
        val group = groups.getOrNull(groupIndex)
            ?: return SearchDraftReduction(state, accepted = false)
        val anchor = group.terms.first()
        if (
            normalized.facet != anchor.facet ||
            normalized.sourceNamespace != anchor.sourceNamespace ||
            normalized in state.query.draft.includeTerms ||
            group.terms.size >= MAX_GROUP_ALTERNATIVES ||
            (state.query.draft.mode == QueryMode.Unified && !normalized.isPortableGeneralTag)
        ) {
            return SearchDraftReduction(state, accepted = false)
        }
        return mutateQuery(state) { query ->
            query.withIncludeTermGroups(
                groups.mapIndexed { index, current ->
                    if (index == groupIndex) SearchTermGroup(current.terms + normalized) else current
                }
            )
        }
    }

    fun splitIncludeAlternative(
        state: SearchUiState,
        groupIndex: Int,
        term: SearchTerm,
    ): SearchDraftReduction {
        val groups = state.query.draft.effectiveIncludeTermGroups
        val group = groups.getOrNull(groupIndex)
            ?: return SearchDraftReduction(state, accepted = false)
        if (group.terms.size <= 1 || term !in group.terms) {
            return SearchDraftReduction(state, accepted = false)
        }
        val replacement = buildList {
            groups.forEachIndexed { index, current ->
                if (index != groupIndex) {
                    add(current)
                } else {
                    add(SearchTermGroup(current.terms.filterNot { it == term }))
                    add(SearchTermGroup.single(term))
                }
            }
        }
        return mutateQuery(state) { query -> query.withIncludeTermGroups(replacement) }
    }

    /**
     * Adds general [terms] as one new group of alternatives, leaving out any the draft already
     * requires. Only general tags are accepted, so the group suits every source.
     */
    fun addIncludeGroup(state: SearchUiState, terms: List<SearchTerm>): SearchDraftReduction {
        val fresh = terms.mapNotNull { it.normalizedOrNull() }
            .filter(SearchTerm::isPortableGeneralTag)
            .distinct()
            .filterNot { it in state.query.draft.includeTerms }
            .take(MAX_GROUP_ALTERNATIVES)
        if (fresh.isEmpty()) return SearchDraftReduction(state, accepted = false)
        return mutateQuery(state) { query ->
            query.withIncludeTermGroups(query.effectiveIncludeTermGroups + SearchTermGroup(fresh))
        }
    }

    fun removeIncludeGroup(state: SearchUiState, groupIndex: Int): SearchDraftReduction {
        val groups = state.query.draft.effectiveIncludeTermGroups
        if (groupIndex !in groups.indices) {
            return SearchDraftReduction(state, accepted = false)
        }
        return mutateQuery(state) { query ->
            query.withIncludeTermGroups(groups.filterIndexed { index, _ -> index != groupIndex })
        }
    }

    fun restoreTerms(
        state: SearchUiState,
        includeTermGroups: List<SearchTermGroup>,
        excludeTerms: List<SearchTerm>,
    ): SearchDraftReduction = mutateQuery(state) { query ->
        query.withIncludeTermGroups(includeTermGroups).copy(excludeTerms = excludeTerms)
    }

    fun addPostTerm(
        state: SearchUiState,
        post: Post,
        term: SearchTerm,
        excluded: Boolean,
    ): SearchDraftReduction {
        val normalized = term.normalizedOrNull()
            ?: return SearchDraftReduction(state, accepted = false)
        var prepared = state
        if (!normalized.isPortableGeneralTag) {
            val sourceMode = QueryMode.Source(post.id.source)
            if (!sourceMode.isAvailable(state.query.availableSources)) {
                return SearchDraftReduction(state, accepted = false)
            }
            if (state.query.draft.mode != sourceMode) {
                val query = state.query.draft.withIncludeTermGroups(
                    state.query.draft.effectiveIncludeTermGroups
                        .filter(SearchTermGroup::isPortableGeneralTagGroup),
                ).copy(
                    mode = sourceMode,
                    excludeTerms = state.query.draft.excludeTerms.filter(SearchTerm::isPortableGeneralTag),
                )
                prepared = state.switchDraft(query, selectedScope = FacetedSearchScope.All)
            }
        }
        return addTerm(prepared, normalized, excluded)
    }

    /**
     * What [addPostTerm] would discard: a typed tag moves the draft to the post's [source], and the
     * typed terms of any other source cannot come along.
     */
    fun termsLostToPostTerm(query: Query, source: SourceKey, term: SearchTerm): List<SearchTerm> {
        val normalized = term.normalizedOrNull() ?: return emptyList()
        if (normalized.isPortableGeneralTag || query.mode == QueryMode.Source(source)) return emptyList()
        return (query.includeTerms + query.excludeTerms).filterNot(SearchTerm::isPortableGeneralTag)
    }

    /**
     * The form a post's [term] takes to join group [groupIndex], or null when it cannot join.
     * A source's general tags may carry a "tag" namespace, so they adopt the group's own.
     */
    fun postTermForGroup(query: Query, source: SourceKey, groupIndex: Int, term: SearchTerm): SearchTerm? {
        val normalized = term.normalizedOrNull() ?: return null
        val group = query.effectiveIncludeTermGroups.getOrNull(groupIndex) ?: return null
        val anchor = group.terms.first()
        val sameType = normalized.facet == anchor.facet && (
            normalized.sourceNamespace.equals(anchor.sourceNamespace, ignoreCase = true) ||
                (normalized.isGeneralTag && anchor.isGeneralTag)
            )
        if (!sameType || group.terms.size >= MAX_GROUP_ALTERNATIVES) return null
        val joined = normalized.copy(sourceNamespace = anchor.sourceNamespace)
        val wrongSource = !joined.isPortableGeneralTag && query.mode != QueryMode.Source(source)
        return joined.takeUnless { wrongSource || it in query.includeTerms }
    }

    /** Post and saved tags come from the source itself, so they skip the check that typed Gelbooru input needs. */
    fun addKnownIncludeAlternative(
        state: SearchUiState,
        source: SourceKey,
        groupIndex: Int,
        term: SearchTerm,
    ): SearchDraftReduction {
        val joined = postTermForGroup(state.query.draft, source, groupIndex, term)
            ?: return SearchDraftReduction(state, accepted = false)
        return mutateQuery(state) { query ->
            query.withIncludeTermGroups(
                query.effectiveIncludeTermGroups.mapIndexed { index, group ->
                    if (index == groupIndex) SearchTermGroup(group.terms + joined) else group
                },
            )
        }
    }

    fun selectScope(state: SearchUiState, scope: FacetedSearchScope): SearchDraftReduction {
        if (scope !in state.query.supportedScopes) {
            return SearchDraftReduction(state, accepted = false)
        }
        return SearchDraftReduction(
            state.copy(
                query = state.query.copy(selectedScope = scope),
                suggestions = state.suggestions.withoutMatches().copy(inputError = null),
            ),
        )
    }

    fun restoreDraft(state: SearchUiState, query: Query): SearchDraftReduction {
        if (!query.mode.isAvailable(state.query.availableSources)) {
            return SearchDraftReduction(state, accepted = false)
        }
        val sanitized = query.normalizedForMode(query.mode)
        return SearchDraftReduction(
            state.switchDraft(
                sanitized.query,
                validationMessage = UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE.takeIf { sanitized.removedSourceOwnedTerms },
                selectedScope = FacetedSearchScope.All,
            ),
        )
    }

    fun clearDraft(state: SearchUiState): SearchDraftReduction {
        val scope = state.query.draftSourceScope
        return SearchDraftReduction(state.switchDraft(emptySearchQuery(scope.queryMode()), scope))
    }

    fun prepareTagSearch(
        state: SearchUiState,
        includeTermGroups: List<SearchTermGroup>,
        excludeTerms: List<SearchTerm>,
        mode: QueryMode,
    ): SearchDraftReduction {
        val includes = includeTermGroups.map { group -> group.terms.mapNotNull(SearchTerm::normalizedOrNull).distinct() }
            .filter(List<SearchTerm>::isNotEmpty)
            .distinctBy(List<SearchTerm>::toSet)
        val excludes = excludeTerms.mapNotNull(SearchTerm::normalizedOrNull).distinct()
            .filterNot { excluded -> includes.any { excluded in it } }
        if ((includes.isEmpty() && excludes.isEmpty()) || !mode.isAvailable(state.query.availableSources)) {
            return SearchDraftReduction(state, accepted = false)
        }
        val query = emptySearchQuery(mode).withIncludeTermGroups(includes.map(::SearchTermGroup))
            .copy(excludeTerms = excludes)
        return SearchDraftReduction(
            state.switchDraft(query, selectedScope = FacetedSearchScope.All).copy(
                content = state.content.copy(
                    results = emptyList(),
                    statuses = emptyList(),
                    canLoadMore = false,
                    error = null,
                ),
            ),
        )
    }

    fun setNhentaiLanguage(state: SearchUiState, filter: NhentaiLanguageFilter): SearchDraftReduction {
        val cleaned = state.query.draft.effectiveIncludeTermGroups.filterNot { group ->
            group.terms.any { it.nhentaiLanguageFilterOrNull() != null }
        }
        val term = NHENTAI_LANGUAGE_TAG_BY_FILTER[filter]?.let { value ->
            SearchTerm(value, SearchFacet.LANGUAGE, NHENTAI_LANGUAGE_NAMESPACE)
        }
        return mutateQuery(state) { query ->
            query.withIncludeTermGroups(
                if (term == null) cleaned else cleaned + SearchTermGroup.single(term),
            )
        }
    }

    fun setNhentaiFullColor(state: SearchUiState, enabled: Boolean): SearchDraftReduction {
        val cleaned = state.query.draft.effectiveIncludeTermGroups.filterNot { group ->
            group.terms.any(SearchTerm::isNhentaiFullColorFilter)
        }
        val groups = if (enabled) {
            cleaned + SearchTermGroup.single(
                SearchTerm(NHENTAI_FULL_COLOR_TAG, SearchFacet.TAG, NHENTAI_TAG_NAMESPACE)
            )
        } else cleaned
        return mutateQuery(state) { it.withIncludeTermGroups(groups) }
    }

    fun commitInput(state: SearchUiState, input: String): SearchDraftReduction {
        if (!canCommitInput(state, input)) {
            val parsed = parseScopedInput(input)
            val message = when {
                parsed.explicitScope != null && state.query.draft.mode == QueryMode.Unified ->
                    UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE
                parsed.explicitScope != null -> UNSUPPORTED_SEARCH_SCOPE_MESSAGE
                state.query.draftExactTagSource != null -> state.query.exactTagInputError()
                else -> state.suggestions.inputError
            }
            return SearchDraftReduction(
                state.copy(suggestions = state.suggestions.copy(inputError = message)),
                accepted = false,
            )
        }
        val parsed = parseScopedInput(resolveCommittedInput(state, input))
        val resolvedTerm = resolveInputTerm(state, parsed)
            ?: return SearchDraftReduction(state, accepted = false)
        var selected = state
        if (parsed.explicitScope != null) {
            val scope = resolveSupportedScope(parsed.explicitScope, state.query.supportedScopes)
            if (scope != null) selected = state.copy(query = state.query.copy(selectedScope = scope))
        }
        return addTerm(selected, resolvedTerm, parsed.isExclude).copy(accepted = true)
    }

    fun canCommitInput(state: SearchUiState, input: String): Boolean {
        val parsed = parseScopedInput(input)
        if (parsed.value.isBlank()) return false
        if (parsed.explicitScope != null) {
            if (state.query.draft.mode == QueryMode.Unified) return false
            if (resolveSupportedScope(parsed.explicitScope, state.query.supportedScopes) == null) return false
        }
        val source = state.query.draftExactTagSource ?: return true
        val normalized = sourceAutocompletePrefix(source, parsed.value)
        // A tag searched before on the source is as confirmed as one its autocomplete returned.
        return (state.suggestions.autocomplete.map(TagSuggestion::text) + state.suggestions.searchedTags.map(SearchTerm::value)).any { tag ->
            sourceTagsMatch(source, tag, normalized)
        }
    }

    fun directNhentaiGalleryIdCandidate(query: Query): String? = query.directNhentaiGalleryIdCandidate()

    /**
     * Replaces the draft with [draft] searched over [scope]. [selectedScope] stays picked when [draft]'s source offers
     * it, and the suggestions shown for the old draft go unless [suggestions] says what remains.
     */
    private fun SearchUiState.switchDraft(
        draft: Query,
        scope: SearchSourceScope = SearchSourceScope.fromQuery(draft),
        validationMessage: String? = null,
        selectedScope: FacetedSearchScope = query.selectedScope,
        suggestions: SearchSuggestionsUiState = SearchSuggestionsUiState(),
    ): SearchUiState = copy(
        query = query.copy(
            draft = draft,
            draftSourceScope = scope,
            selectedScope = query.validSelectedScope(selectedScope, draft),
            validationMessage = validationMessage,
        ),
        suggestions = suggestions,
    )

    private fun SearchQueryUiState.validSelectedScope(selected: FacetedSearchScope, draft: Query): FacetedSearchScope =
        selected.takeIf { it in scopesFor(draft.mode) } ?: FacetedSearchScope.All

    /** Keeps the typed input and its error, dropping the matches found for it. */
    private fun SearchSuggestionsUiState.withoutMatches() =
        copy(autocomplete = emptyList(), facetedAutocomplete = emptyList(), searchedTags = emptyList())

    private fun resolveCommittedInput(state: SearchUiState, input: String): String {
        val trimmed = input.trim()
        val source = (state.query.draft.mode as? QueryMode.Source)?.source ?: return trimmed
        if (state.query.supportedScopes.isNotEmpty()) return trimmed
        val parsed = parseScopedInput(trimmed)
        if (parsed.explicitScope != null) return trimmed
        // A typed translation ("Hatsune Miku") commits the native tag it labels (初音ミク).
        val matched = state.suggestions.autocomplete.firstOrNull { suggestion ->
            sourceTagsMatch(source, suggestion.text, parsed.value)
        }?.text ?: state.suggestions.autocomplete.firstOrNull { suggestion ->
            suggestion.alternateText?.let { sourceTagsMatch(source, it, parsed.value) } == true
        }?.text
        val committed = matched?.trim().orEmpty().ifBlank { parsed.value }
        return if (parsed.isExclude) "-$committed" else committed
    }

    private fun resolveInputTerm(state: SearchUiState, input: ParsedScopedInput): SearchTerm? {
        input.explicitScope?.let { prefix ->
            val scope = resolveSupportedScope(prefix, state.query.supportedScopes) ?: return null
            return SearchTerm(
                value = input.value,
                facet = requireNotNull(scope.facet),
                sourceNamespace = scope.sourceNamespace ?: prefix.sourceNamespace,
            )
        }
        val selected = state.query.selectedScope.takeIf { !it.isAll && it in state.query.supportedScopes }
        return if (selected == null) SearchTerm(input.value) else SearchTerm(
            value = input.value,
            facet = requireNotNull(selected.facet),
            sourceNamespace = selected.sourceNamespace,
        )
    }
}

fun modeKey(mode: QueryMode): String = when (mode) {
    QueryMode.Unified -> "unified"
    is QueryMode.Source -> "source:${mode.source.id}"
}

fun QueryMode.isAvailable(sources: Collection<SourceKey>): Boolean = when (this) {
    QueryMode.Unified -> true
    is QueryMode.Source -> source in sources
}

fun SearchSourceScope.queryMode(): QueryMode = when (this) {
    SearchSourceScope.GlobalUnified -> QueryMode.Unified
    is SearchSourceScope.Single -> QueryMode.Source(source)
    is SearchSourceScope.Temporary -> QueryMode.Unified
}

private fun Query.hasSourceOwnedTerms(): Boolean =
    (includeTerms + excludeTerms).any { !it.isPortableGeneralTag }

private fun SearchTerm.normalizedOrNull(): SearchTerm? {
    val value = value.trim().takeIf(String::isNotBlank) ?: return null
    return copy(value = value, sourceNamespace = sourceNamespace?.trim()?.takeIf(String::isNotBlank))
}

private val SearchTerm.isGeneralTag: Boolean
    get() = facet == SearchFacet.TAG && (sourceNamespace == null || sourceNamespace.equals("tag", ignoreCase = true))

private const val MAX_GROUP_ALTERNATIVES = 8

internal fun Query.nhentaiLanguageFilter(): NhentaiLanguageFilter =
    includeTerms.firstNotNullOfOrNull(SearchTerm::nhentaiLanguageFilterOrNull)
        ?: NhentaiLanguageFilter.ANY

private fun SearchTerm.nhentaiLanguageFilterOrNull(): NhentaiLanguageFilter? {
    if (facet != SearchFacet.LANGUAGE && !isPortableGeneralTag) return null
    return when (normalizeNhentaiTag(value)) {
        "english" -> NhentaiLanguageFilter.ENGLISH
        "chinese" -> NhentaiLanguageFilter.CHINESE
        "japanese" -> NhentaiLanguageFilter.JAPANESE
        else -> null
    }
}

internal fun Query.nhentaiFullColorFilter(): Boolean = includeTerms.any(SearchTerm::isNhentaiFullColorFilter)

private fun SearchTerm.isNhentaiFullColorFilter(): Boolean =
    facet == SearchFacet.TAG && sourceNamespace in setOf(null, NHENTAI_TAG_NAMESPACE) &&
        normalizeNhentaiTag(value) == NHENTAI_FULL_COLOR_TAG

private fun normalizeNhentaiTag(value: String): String = value.trim().lowercase()
    .replace('_', ' ').replace(Regex("\\s+"), " ")

private fun Query.directNhentaiGalleryIdCandidate(): String? {
    if (mode != QueryMode.Unified && mode != QueryMode.Source(SourceKey.NHENTAI)) return null
    if (excludeTerms.isNotEmpty()) return null
    val terms = includeTerms.filterNot {
        it.nhentaiLanguageFilterOrNull() != null || it.isNhentaiFullColorFilter()
    }
    if (terms.size != 1 || !terms.single().isPortableGeneralTag) return null
    return terms.single().value.trim().takeIf { value -> value.isNotBlank() && value.all(Char::isDigit) }
}

private val NHENTAI_LANGUAGE_TAG_BY_FILTER = mapOf(
    NhentaiLanguageFilter.ENGLISH to "english",
    NhentaiLanguageFilter.CHINESE to "chinese",
    NhentaiLanguageFilter.JAPANESE to "japanese",
)
private const val NHENTAI_LANGUAGE_NAMESPACE = "language"
private const val NHENTAI_TAG_NAMESPACE = "tag"
private const val NHENTAI_FULL_COLOR_TAG = "full color"
/** Asks for a suggested tag on the draft's source, which matches only exactly spelled tags. */
private fun SearchQueryUiState.exactTagInputError(): String =
    "For ${exactTagSources[draftExactTagSource]}, pick a suggested tag from autocomplete."
