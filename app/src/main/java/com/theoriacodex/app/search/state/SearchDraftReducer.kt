package com.theoriacodex.app.search.state

import com.theoriacodex.app.search.DateRangePreset
import com.theoriacodex.app.search.NhentaiLanguageFilter
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeGelbooruToken
import com.theoriacodex.domain.tags.sourceTagsMatch

internal data class SearchDraftContext(
    val availableSources: Set<SourceKey>,
    val appliedByMode: Map<String, Query>,
)

internal data class SearchDraftReduction(
    val state: SearchUiState,
    val changed: Boolean = true,
    val accepted: Boolean = true,
)

/** Pure route-input reducer. SearchViewModel is the only holder of the returned state. */
internal object SearchDraftReducer {
    fun selectMode(
        state: SearchUiState,
        mode: QueryMode,
        context: SearchDraftContext,
        supportedScopes: (QueryMode) -> List<FacetedSearchScope>,
    ): SearchDraftReduction {
        val hadSourceOwnedTerms = state.query.draft.hasSourceOwnedTerms()
        val resolvedMode = mode.takeIf { it.isAvailable(context.availableSources) } ?: QueryMode.Unified
        val restored = context.appliedByMode[modeKey(resolvedMode)] ?: emptySearchQuery(resolvedMode)
        val sanitized = restored.copy(mode = resolvedMode).forMode(resolvedMode)
        val scopes = supportedScopes(resolvedMode)
        val selectedScope = state.query.selectedScope.takeIf { it in scopes }
            ?: FacetedSearchScope.All
        val validation = UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE.takeIf {
            resolvedMode == QueryMode.Unified &&
                (hadSourceOwnedTerms || sanitized.removedSourceOwnedTerms)
        }
        return SearchDraftReduction(
            state.copy(
                query = state.query.copy(
                    draft = sanitized.query,
                    draftSourceScope = SearchSourceScope.fromQuery(sanitized.query),
                    supportedScopes = scopes,
                    selectedScope = selectedScope,
                    validationMessage = validation,
                    nhentaiLanguageFilter = sanitized.query.nhentaiLanguageFilter(),
                    nhentaiFullColorFilter = sanitized.query.nhentaiFullColorFilter(),
                ),
                suggestions = state.suggestions.copy(
                    input = "",
                    autocomplete = emptyList(),
                    facetedAutocomplete = emptyList(),
                    canCommitInput = false,
                ),
            ),
        )
    }

    fun toggleTemporarySource(
        state: SearchUiState,
        source: SourceKey,
        context: SearchDraftContext,
        supportedScopes: (QueryMode) -> List<FacetedSearchScope>,
    ): SearchDraftReduction {
        if (source !in context.availableSources) return SearchDraftReduction(state, changed = false, accepted = false)
        val nextScope = when (val current = state.query.draftSourceScope) {
            SearchSourceScope.GlobalUnified -> return SearchDraftReduction(state, changed = false, accepted = false)
            is SearchSourceScope.Single -> {
                if (current.source == source) return SearchDraftReduction(state, changed = false, accepted = false)
                SearchSourceScope.fromSources(listOf(current.source, source))
            }
            is SearchSourceScope.Temporary -> SearchSourceScope.fromSources(
                if (source in current.sources) current.sources - source else current.sources + source,
            )
        }
        if (nextScope == state.query.draftSourceScope) {
            return SearchDraftReduction(state, changed = false, accepted = false)
        }
        val sanitized = state.query.draft.forMode(nextScope.queryMode())
        val scopes = supportedScopes(sanitized.query.mode)
        return SearchDraftReduction(
            state.copy(
                query = state.query.copy(
                    draft = sanitized.query,
                    draftSourceScope = nextScope,
                    supportedScopes = scopes,
                    selectedScope = state.query.selectedScope.takeIf { it in scopes }
                        ?: FacetedSearchScope.All,
                    validationMessage = UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE
                        .takeIf { sanitized.removedSourceOwnedTerms },
                    nhentaiLanguageFilter = sanitized.query.nhentaiLanguageFilter(),
                    nhentaiFullColorFilter = sanitized.query.nhentaiFullColorFilter(),
                ),
                suggestions = state.suggestions.copy(
                    autocomplete = emptyList(),
                    facetedAutocomplete = emptyList(),
                ),
            ),
        )
    }

    fun mutateQuery(state: SearchUiState, transform: (Query) -> Query): SearchDraftReduction {
        val next = transform(state.query.draft)
        return SearchDraftReduction(
            state.copy(
                query = state.query.copy(
                    draft = next,
                    nhentaiLanguageFilter = next.nhentaiLanguageFilter(),
                    nhentaiFullColorFilter = next.nhentaiFullColorFilter(),
                ),
            ),
            changed = next != state.query.draft,
        )
    }

    fun addTerm(
        state: SearchUiState,
        term: SearchTerm,
        excluded: Boolean,
    ): SearchDraftReduction {
        val normalized = term.normalizedOrNull()
            ?: return SearchDraftReduction(state, changed = false, accepted = false)
        if (state.query.draft.mode == QueryMode.Unified && !normalized.isPortableGeneralTag) {
            return SearchDraftReduction(
                state.copy(query = state.query.copy(validationMessage = UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE)),
                changed = true,
                accepted = false,
            )
        }
        val current = if (excluded) state.query.draft.excludeTerms else state.query.draft.includeTerms
        if (normalized in current) return SearchDraftReduction(state, changed = false, accepted = false)
        val reduced = mutateQuery(state) { query ->
            if (excluded) query.copy(excludeTerms = query.excludeTerms + normalized)
            else query.copy(includeTerms = query.includeTerms + normalized)
        }
        return reduced.copy(
            state = reduced.state.copy(
                query = reduced.state.query.copy(validationMessage = null),
            ),
        )
    }

    fun removeTerm(state: SearchUiState, term: SearchTerm, excluded: Boolean): SearchDraftReduction {
        return mutateQuery(state) { query ->
            if (excluded) query.copy(excludeTerms = query.excludeTerms.filterNot { it == term })
            else query.copy(includeTerms = query.includeTerms.filterNot { it == term })
        }
    }

    fun addPostTerm(
        state: SearchUiState,
        post: Post,
        term: SearchTerm,
        excluded: Boolean,
        availableSources: Set<SourceKey>,
        supportedScopes: (QueryMode) -> List<FacetedSearchScope>,
    ): SearchDraftReduction {
        val normalized = term.normalizedOrNull()
            ?: return SearchDraftReduction(state, changed = false, accepted = false)
        var prepared = state
        if (!normalized.isPortableGeneralTag) {
            val sourceMode = QueryMode.Source(post.id.source)
            if (!sourceMode.isAvailable(availableSources)) {
                return SearchDraftReduction(state, changed = false, accepted = false)
            }
            if (state.query.draft.mode != sourceMode) {
                val query = state.query.draft.copy(
                    mode = sourceMode,
                    includeTerms = state.query.draft.includeTerms.filter(SearchTerm::isPortableGeneralTag),
                    excludeTerms = state.query.draft.excludeTerms.filter(SearchTerm::isPortableGeneralTag),
                )
                val scopes = supportedScopes(sourceMode)
                prepared = state.copy(
                    query = state.query.copy(
                        draft = query,
                        draftSourceScope = SearchSourceScope.Single(post.id.source),
                        supportedScopes = scopes,
                        selectedScope = FacetedSearchScope.All,
                        validationMessage = null,
                    ),
                    suggestions = state.suggestions.copy(
                        input = "",
                        autocomplete = emptyList(),
                        facetedAutocomplete = emptyList(),
                        canCommitInput = false,
                    ),
                )
            }
        }
        return addTerm(prepared, normalized, excluded)
    }

    fun selectScope(state: SearchUiState, scope: FacetedSearchScope): SearchDraftReduction {
        if (scope !in state.query.supportedScopes) {
            return SearchDraftReduction(state, changed = false, accepted = false)
        }
        return SearchDraftReduction(
            state.copy(
                query = state.query.copy(selectedScope = scope, validationMessage = null),
                suggestions = state.suggestions.copy(
                    autocomplete = emptyList(),
                    facetedAutocomplete = emptyList(),
                ),
            ),
            changed = scope != state.query.selectedScope,
        )
    }

    fun restoreDraft(
        state: SearchUiState,
        query: Query,
        availableSources: Set<SourceKey>,
        supportedScopes: (QueryMode) -> List<FacetedSearchScope>,
    ): SearchDraftReduction {
        if (!query.mode.isAvailable(availableSources)) {
            return SearchDraftReduction(state, changed = false, accepted = false)
        }
        val sanitized = query.forMode(query.mode)
        val scopes = supportedScopes(sanitized.query.mode)
        return SearchDraftReduction(
            state.copy(
                query = state.query.copy(
                    draft = sanitized.query,
                    draftSourceScope = SearchSourceScope.fromQuery(sanitized.query),
                    supportedScopes = scopes,
                    selectedScope = FacetedSearchScope.All,
                    validationMessage = UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE
                        .takeIf { sanitized.removedSourceOwnedTerms },
                    nhentaiLanguageFilter = sanitized.query.nhentaiLanguageFilter(),
                    nhentaiFullColorFilter = sanitized.query.nhentaiFullColorFilter(),
                ),
                suggestions = state.suggestions.copy(
                    input = "",
                    autocomplete = emptyList(),
                    facetedAutocomplete = emptyList(),
                    canCommitInput = false,
                ),
            ),
        )
    }

    fun resetDraft(state: SearchUiState): SearchDraftReduction {
        val query = state.query.applied
        return SearchDraftReduction(
            state.copy(
                query = state.query.copy(
                    draft = query,
                    draftSourceScope = state.query.appliedSourceScope,
                    validationMessage = null,
                    nhentaiLanguageFilter = query.nhentaiLanguageFilter(),
                    nhentaiFullColorFilter = query.nhentaiFullColorFilter(),
                ),
                suggestions = SearchSuggestionsUiState(trending = state.suggestions.trending),
            ),
        )
    }

    fun clearDraft(state: SearchUiState): SearchDraftReduction {
        val mode = state.query.draftSourceScope.queryMode()
        return SearchDraftReduction(resetInputState(state, emptySearchQuery(mode)))
    }

    fun prepareTagSearch(
        state: SearchUiState,
        includeTags: List<String>,
        excludeTags: List<String>,
        mode: QueryMode,
        availableSources: Set<SourceKey>,
        supportedScopes: (QueryMode) -> List<FacetedSearchScope>,
    ): SearchDraftReduction {
        val includes = includeTags.map(String::trim).filter(String::isNotBlank).distinct()
        val excludes = excludeTags.map(String::trim).filter(String::isNotBlank)
            .filterNot { it in includes }.distinct()
        if ((includes.isEmpty() && excludes.isEmpty()) || !mode.isAvailable(availableSources)) {
            return SearchDraftReduction(state, changed = false, accepted = false)
        }
        val query = emptySearchQuery(mode).copy(
            includeTerms = includes.map(::SearchTerm),
            excludeTerms = excludes.map(::SearchTerm),
        )
        val scopes = supportedScopes(mode)
        val reset = resetInputState(state, query)
        return SearchDraftReduction(
            reset.copy(
                query = reset.query.copy(
                    draft = query,
                    draftSourceScope = SearchSourceScope.fromQuery(query),
                    supportedScopes = scopes,
                    selectedScope = FacetedSearchScope.All,
                    validationMessage = null,
                ),
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
        val cleaned = state.query.draft.includeTerms.filterNot { it.nhentaiLanguageFilterOrNull() != null }
        val term = NHENTAI_LANGUAGE_TAG_BY_FILTER[filter]?.let { value ->
            SearchTerm(value, SearchFacet.LANGUAGE, NHENTAI_LANGUAGE_NAMESPACE)
        }
        return mutateQuery(state) { query -> query.copy(includeTerms = if (term == null) cleaned else cleaned + term) }
    }

    fun setNhentaiFullColor(state: SearchUiState, enabled: Boolean): SearchDraftReduction {
        val cleaned = state.query.draft.includeTerms.filterNot(SearchTerm::isNhentaiFullColorFilter)
        val terms = if (enabled) {
            cleaned + SearchTerm(NHENTAI_FULL_COLOR_TAG, SearchFacet.TAG, NHENTAI_TAG_NAMESPACE)
        } else cleaned
        return mutateQuery(state) { it.copy(includeTerms = terms) }
    }

    fun commitInput(state: SearchUiState, input: String): SearchDraftReduction {
        if (!canCommitInput(state, input)) {
            val parsed = parseScopedInput(input)
            val message = when {
                parsed.explicitScope != null && state.query.draft.mode == QueryMode.Unified ->
                    UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE
                parsed.explicitScope != null -> UNSUPPORTED_SEARCH_SCOPE_MESSAGE
                state.query.draft.mode == QueryMode.Source(SourceKey.GELBOORU) ->
                    GELBOORU_SUGGESTION_REQUIRED_MESSAGE
                else -> state.query.validationMessage
            }
            return SearchDraftReduction(
                state.copy(query = state.query.copy(validationMessage = message)),
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
        if (state.query.draft.mode != QueryMode.Source(SourceKey.GELBOORU)) return true
        val normalized = normalizeGelbooruToken(parsed.value)
        return state.suggestions.autocomplete.any { suggestion ->
            sourceTagsMatch(SourceKey.GELBOORU, suggestion.text, normalized)
        }
    }

    fun directNhentaiGalleryIdCandidate(query: Query): String? = query.directNhentaiGalleryIdCandidate()

    private fun resetInputState(state: SearchUiState, query: Query): SearchUiState = state.copy(
        query = state.query.copy(
            draft = query,
            validationMessage = null,
            nhentaiLanguageFilter = query.nhentaiLanguageFilter(),
            nhentaiFullColorFilter = query.nhentaiFullColorFilter(),
        ),
        suggestions = SearchSuggestionsUiState(trending = state.suggestions.trending),
    )

    private fun resolveCommittedInput(state: SearchUiState, input: String): String {
        val trimmed = input.trim()
        val source = (state.query.draft.mode as? QueryMode.Source)?.source ?: return trimmed
        if (state.query.supportedScopes.isNotEmpty()) return trimmed
        if (source !in SUGGESTION_CANONICALIZATION_SOURCES) return trimmed
        val parsed = parseScopedInput(trimmed)
        if (parsed.explicitScope != null) return trimmed
        val matched = state.suggestions.autocomplete.firstOrNull { suggestion ->
            sourceTagsMatch(source, suggestion.text, parsed.value)
        }?.text?.trim().orEmpty().ifBlank { parsed.value }
        return if (parsed.isExclude) "-$matched" else matched
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

internal fun modeKey(mode: QueryMode): String = when (mode) {
    QueryMode.Unified -> "unified"
    is QueryMode.Source -> "source:${mode.source.name}"
}

internal fun QueryMode.isAvailable(sources: Set<SourceKey>): Boolean = when (this) {
    QueryMode.Unified -> true
    is QueryMode.Source -> source in sources
}

internal fun SearchSourceScope.queryMode(): QueryMode = when (this) {
    SearchSourceScope.GlobalUnified -> QueryMode.Unified
    is SearchSourceScope.Single -> QueryMode.Source(source)
    is SearchSourceScope.Temporary -> QueryMode.Unified
}

private data class SanitizedQuery(val query: Query, val removedSourceOwnedTerms: Boolean)

private fun Query.forMode(mode: QueryMode): SanitizedQuery {
    if (mode != QueryMode.Unified) return SanitizedQuery(copy(mode = mode), false)
    val includes = includeTerms.filter(SearchTerm::isPortableGeneralTag)
    val excludes = excludeTerms.filter(SearchTerm::isPortableGeneralTag)
    return SanitizedQuery(
        copy(mode = mode, includeTerms = includes, excludeTerms = excludes),
        includes.size != includeTerms.size || excludes.size != excludeTerms.size,
    )
}

private fun Query.hasSourceOwnedTerms(): Boolean =
    (includeTerms + excludeTerms).any { !it.isPortableGeneralTag }

private fun SearchTerm.normalizedOrNull(): SearchTerm? {
    val value = value.trim().takeIf(String::isNotBlank) ?: return null
    return copy(value = value, sourceNamespace = sourceNamespace?.trim()?.takeIf(String::isNotBlank))
}

private data class SearchScopePrefix(
    val facet: SearchFacet,
    val sourceNamespace: String? = null,
    val requiresExactNamespace: Boolean = false,
)

private data class ParsedScopedInput(
    val value: String,
    val isExclude: Boolean,
    val explicitScope: SearchScopePrefix?,
)

private fun parseScopedInput(input: String): ParsedScopedInput {
    val trimmed = input.trim()
    val excluded = trimmed.startsWith('-')
    val unsigned = trimmed.removePrefix("-").trim()
    val separator = unsigned.indexOf(':')
    if (separator <= 0) return ParsedScopedInput(unsigned, excluded, null)
    val prefix = SEARCH_SCOPE_PREFIXES[unsigned.substring(0, separator).trim().lowercase()]
        ?: return ParsedScopedInput(unsigned, excluded, null)
    return ParsedScopedInput(unsigned.substring(separator + 1).trim(), excluded, prefix)
}

private fun resolveSupportedScope(
    prefix: SearchScopePrefix,
    scopes: List<FacetedSearchScope>,
): FacetedSearchScope? {
    scopes.firstOrNull { it.facet == prefix.facet && it.sourceNamespace == prefix.sourceNamespace }
        ?.let { return it }
    if (prefix.requiresExactNamespace) return null
    return scopes.firstOrNull { it.facet == prefix.facet && it.sourceNamespace == null }
        ?: scopes.firstOrNull { it.facet == prefix.facet }
}

private fun Query.nhentaiLanguageFilter(): NhentaiLanguageFilter =
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

private fun Query.nhentaiFullColorFilter(): Boolean = includeTerms.any(SearchTerm::isNhentaiFullColorFilter)

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

private val SEARCH_SCOPE_PREFIXES = mapOf(
    "tag" to SearchScopePrefix(SearchFacet.TAG, "tag"),
    "female" to SearchScopePrefix(SearchFacet.TAG, "female", true),
    "male" to SearchScopePrefix(SearchFacet.TAG, "male", true),
    "artist" to SearchScopePrefix(SearchFacet.ARTIST),
    "character" to SearchScopePrefix(SearchFacet.CHARACTER),
    "series" to SearchScopePrefix(SearchFacet.SERIES),
    "parody" to SearchScopePrefix(SearchFacet.SERIES, "parody", true),
    "group" to SearchScopePrefix(SearchFacet.GROUP),
    "type" to SearchScopePrefix(SearchFacet.TYPE),
    "category" to SearchScopePrefix(SearchFacet.TYPE, "category", true),
    "language" to SearchScopePrefix(SearchFacet.LANGUAGE),
)

private val SUGGESTION_CANONICALIZATION_SOURCES = setOf(
    SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.NHENTAI, SourceKey.HITOMI,
    SourceKey.IWARA, SourceKey.RULE34XXX, SourceKey.RULE34PAHEAL,
    SourceKey.RULE34VIDEO, SourceKey.RULE34GEN,
)
private val NHENTAI_LANGUAGE_TAG_BY_FILTER = mapOf(
    NhentaiLanguageFilter.ENGLISH to "english",
    NhentaiLanguageFilter.CHINESE to "chinese",
    NhentaiLanguageFilter.JAPANESE to "japanese",
)
private const val NHENTAI_LANGUAGE_NAMESPACE = "language"
private const val NHENTAI_TAG_NAMESPACE = "tag"
private const val NHENTAI_FULL_COLOR_TAG = "full color"
private const val GELBOORU_SUGGESTION_REQUIRED_MESSAGE =
    "For Gelbooru, pick a suggested tag from autocomplete."
private const val UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE =
    "Artists, series, characters, groups, types, and languages require a specific source."
internal const val UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE =
    "Source-specific search terms were removed when switching to Unified."
private const val UNSUPPORTED_SEARCH_SCOPE_MESSAGE =
    "That search scope is not supported by this source."
