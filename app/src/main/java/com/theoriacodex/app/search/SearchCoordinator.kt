package com.theoriacodex.app.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.theoriacodex.app.media.recoverRemoteMedia
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.repository.QueryRepository
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.query.QueryHash
import com.theoriacodex.domain.tags.normalizeFavoriteTagForStorage
import com.theoriacodex.domain.tags.normalizeGelbooruToken
import com.theoriacodex.domain.tags.normalizeMatchToken
import com.theoriacodex.domain.tags.sourceTagKey
import com.theoriacodex.domain.tags.sourceTagsMatch
import com.theoriacodex.app.recommend.recommendationTaxonomyFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class SearchCoordinator(
    private val registry: SourceAdapterRegistry,
    private val queryRepository: QueryRepository = InMemoryQueryRepository(),
    private val settingsRepository: SettingsRepository = InMemorySettingsRepository(),
    private val uiRestoreRepository: UiRestoreRepository = InMemoryUiRestoreRepository(),
    private val recentsRepository: RecentsRepository = InMemoryRecentsRepository(),
    private val tagSuggestionStore: TagSuggestionStore = NoOpTagSuggestionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var runtimeSettings: AppSettings = AppSettings()
    private var hasExecutedSearch = false
    private val appliedByMode = mutableMapOf<String, Query>()
    private var unifiedNextPageTokens: Map<SourceKey, String?> = emptyMap()
    private var unifiedQueryOverrides: Map<SourceKey, Query> = emptyMap()
    private var sourceNextPageToken: String? = null
    private val lastTrendingRefreshAtBySource = mutableMapOf<SourceKey, Long>()
    private val resolvedPostOverridesByQueryHash = linkedMapOf<String, LinkedHashMap<PostId, Post>>()
    private val recentResolveFailuresByQueryHash = mutableMapOf<String, MutableMap<PostId, ResolveFailureRecord>>()

    var draftQuery by mutableStateOf(defaultQuery())
        private set

    var appliedQuery by mutableStateOf(defaultQuery())
        private set

    var results by mutableStateOf<List<Post>>(emptyList())
        private set

    var statuses by mutableStateOf<List<SourceRunStatus>>(emptyList())
        private set

    var trendingTags by mutableStateOf<List<TagSuggestion>>(emptyList())
        private set

    var autocompleteSuggestions by mutableStateOf<List<TagSuggestion>>(emptyList())
        private set

    /**
     * The lossless suggestion lane used by faceted sources. The legacy suggestion list above
     * remains available for sources that only expose general tags.
     */
    var facetedAutocompleteSuggestions by mutableStateOf<List<FacetedTagSuggestion>>(emptyList())
        private set

    var selectedSearchScope by mutableStateOf(FacetedSearchScope.All)
        private set

    var tagInputValidationMessage by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var loadingMore by mutableStateOf(false)
        private set

    var canLoadMore by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var displayResultsVersion by mutableStateOf(0)
        private set

    val availableSources: List<SourceKey>
        get() = registry.availableSources().toList().sortedWith(
            compareBy<SourceKey> { source -> sourceDisplayOrderIndex(source) }
                .thenBy { source -> source.name }
        )

    val modeOptions: List<QueryMode>
        get() = listOf(QueryMode.Unified) + availableSources.map(QueryMode::Source)

    val supportedSearchScopes: List<FacetedSearchScope>
        get() {
            val mode = draftQuery.mode as? QueryMode.Source ?: return emptyList()
            val adapter = registry.adapterFor(mode.source) as? FacetedSearchSourceAdapter
                ?: return emptyList()
            return adapter.supportedSearchScopes
                .sortedWith(SEARCH_SCOPE_COMPARATOR)
        }

    val hasPendingChanges: Boolean
        get() = draftQuery != appliedQuery

    val hasAnySearchRun: Boolean
        get() = hasExecutedSearch

    val enabledSourceCount: Int
        get() = effectiveEnabledSources().size

    val appliedQueryHash: String
        get() = QueryHash.from(appliedQuery)

    fun tagVideoCount(source: SourceKey, tag: String): Int? {
        val normalized = tag.trim().lowercase()
        if (normalized.isBlank()) return null

        val cachedCount = tagSuggestionStore
            .get(source = source, limit = TAG_LOOKUP_LIMIT)
            .firstOrNull { suggestion ->
                tagsMatchForSource(source, suggestion.text, normalized)
            }
            ?.count
        if (cachedCount != null) return cachedCount

        val autocompleteCount = autocompleteSuggestions
            .firstOrNull { suggestion ->
                tagsMatchForSource(source, suggestion.text, normalized)
            }
            ?.count
        if (autocompleteCount != null) return autocompleteCount

        return trendingTags
            .firstOrNull { suggestion ->
                tagsMatchForSource(source, suggestion.text, normalized)
            }
            ?.count
    }

    suspend fun fetchTagVideoCount(source: SourceKey, tag: String): Int? {
        val normalized = tag.trim()
        if (normalized.isBlank()) return null
        return fetchTagVideoCounts(source, tags = listOf(normalized))[normalized]
    }

    suspend fun fetchTagVideoCounts(source: SourceKey, tags: List<String>): Map<String, Int?> {
        val requested = tags
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (requested.isEmpty()) return emptyMap()

        val resolved = requested.associateWith { tag -> tagVideoCount(source, tag) }.toMutableMap()
        var missing = requested.filter { tag -> resolved[tag] == null }
        if (missing.isEmpty()) return resolved

        val adapter = registry.adapterFor(source) ?: return resolved
        if (adapter is TagCountLookupSourceAdapter) {
            val sourceTags = missing.map { tag -> autocompletePrefixForSource(source, tag) }
            val batchCounts = runCatching {
                adapter.fetchTagCounts(sourceTags)
            }.getOrDefault(emptyMap())
            if (batchCounts.isNotEmpty()) {
                tagSuggestionStore.put(
                    source = source,
                    suggestions = batchCounts.map { (tagText, count) ->
                        TagSuggestion(
                            text = tagText,
                            type = "tag_count_lookup",
                            count = count,
                        )
                    },
                )
                missing.forEach { tag ->
                    val matched = batchCounts.entries
                        .firstOrNull { (name, _) -> tagsMatchForSource(source, name, tag) }
                        ?.value
                    if (matched != null) {
                        resolved[tag] = matched
                    }
                }
            }
            missing = requested.filter { tag -> resolved[tag] == null }
        }

        missing.forEach { tag ->
            val sourcePrefix = autocompletePrefixForSource(source, tag)
            val fetched = runCatching {
                adapter.autocompleteTags(prefix = sourcePrefix, limit = TAG_FETCH_LIMIT)
            }.getOrDefault(emptyList())
            if (fetched.isNotEmpty()) {
                tagSuggestionStore.put(source, fetched)
            }
            val count = fetched
                .firstOrNull { suggestion -> tagsMatchForSource(source, suggestion.text, tag) }
                ?.count
                ?: tagVideoCount(source, tag)
            if (count != null) {
                resolved[tag] = count
            }
        }

        return resolved
    }

    suspend fun initialize() {
        runtimeSettings = settingsRepository.observeSettings().first()

        modeOptions.forEach { mode ->
            val stored = queryRepository.observeAppliedQuery(modeKey(mode)).first()
            if (stored != null && isModeAvailable(stored.mode)) {
                appliedByMode[modeKey(mode)] = stored
            }
        }

        val lastApplied = queryRepository.observeAppliedQuery(LAST_ACTIVE_QUERY_KEY).first()
            ?.takeIf { query -> isModeAvailable(query.mode) }
        val restored = if (lastApplied != null) {
            appliedByMode[modeKey(lastApplied.mode)] ?: defaultQuery(lastApplied.mode)
        } else {
            appliedByMode[modeKey(QueryMode.Unified)] ?: defaultQuery()
        }
        val sanitized = restored.forMode(restored.mode)
        appliedQuery = sanitized.query
        draftQuery = sanitized.query
        if (sanitized.removedSourceOwnedTerms) {
            tagInputValidationMessage = UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE
        }
        resetUnsupportedSearchScope()
        hasExecutedSearch =
            appliedByMode.containsKey(modeKey(appliedQuery.mode)) ||
            queryRepository.getScrollOffset(appliedQueryHash) != null
    }

    fun onSettingsChanged(settings: AppSettings): Boolean {
        val previousRuntime = runtimeSettings.runtime
        runtimeSettings = settings

        if (!isModeAvailable(draftQuery.mode)) {
            draftQuery = defaultQuery(QueryMode.Unified)
            resetUnsupportedSearchScope()
        }
        if (!isModeAvailable(appliedQuery.mode)) {
            appliedQuery = defaultQuery(QueryMode.Unified)
        }

        return hasExecutedSearch && previousRuntime != settings.runtime
    }

    fun addTagInput(input: String) {
        val parsed = parseScopedInput(input)
        if (parsed.value.isBlank()) return

        val term = resolveInputTerm(parsed) ?: return
        if (parsed.isExclude) {
            addExcludeTerm(term)
        } else {
            addIncludeTerm(term)
        }
    }

    fun canCommitTagInput(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return false
        val parsed = parseScopedInput(trimmed)
        if (parsed.value.isBlank()) return false
        if (parsed.explicitScope != null && !canUseParsedScope(parsed)) return false
        if (!requiresGelbooruSuggestionSelection()) return true
        val normalizedTag = normalizeTypedTag(trimmed)
        if (normalizedTag.isBlank()) return false
        return isSuggestedTag(normalizedTag)
    }

    fun commitTagInput(input: String): Boolean {
        if (!canCommitTagInput(input)) {
            val parsed = parseScopedInput(input)
            if (parsed.explicitScope != null && !canUseParsedScope(parsed)) {
                tagInputValidationMessage = when (draftQuery.mode) {
                    QueryMode.Unified -> UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE
                    is QueryMode.Source -> UNSUPPORTED_SEARCH_SCOPE_MESSAGE
                }
            } else if (requiresGelbooruSuggestionSelection()) {
                tagInputValidationMessage = GELBOORU_SUGGESTION_REQUIRED_MESSAGE
            }
            return false
        }
        addTagInput(resolveCommittedTagInput(input))
        tagInputValidationMessage = null
        return true
    }

    fun clearTagInputValidationMessage() {
        tagInputValidationMessage = null
    }

    fun clearAutocompleteSuggestions() {
        autocompleteSuggestions = emptyList()
        facetedAutocompleteSuggestions = emptyList()
    }

    fun addIncludeTag(tag: String) {
        val normalized = tag.trim()
        if (normalized.isBlank()) return
        addIncludeTerm(SearchTerm(value = normalized))
    }

    fun addExcludeTag(tag: String) {
        val normalized = tag.trim()
        if (normalized.isBlank()) return
        addExcludeTerm(SearchTerm(value = normalized))
    }

    fun addIncludeTerm(term: SearchTerm): Boolean {
        val normalized = term.normalizedOrNull() ?: return false
        if (!canAddTermToMode(normalized)) return false
        if (normalized in draftQuery.includeTerms) return false
        draftQuery = draftQuery.copy(includeTerms = draftQuery.includeTerms + normalized)
        tagInputValidationMessage = null
        return true
    }

    fun addExcludeTerm(term: SearchTerm): Boolean {
        val normalized = term.normalizedOrNull() ?: return false
        if (!canAddTermToMode(normalized)) return false
        if (normalized in draftQuery.excludeTerms) return false
        draftQuery = draftQuery.copy(excludeTerms = draftQuery.excludeTerms + normalized)
        tagInputValidationMessage = null
        return true
    }

    fun addPostIncludeTerm(post: Post, term: SearchTerm): Boolean {
        val normalized = term.normalizedOrNull() ?: return false
        if (!prepareModeForPostTerm(post, normalized)) return false
        addIncludeTerm(normalized)
        return normalized in draftQuery.includeTerms
    }

    fun addPostExcludeTerm(post: Post, term: SearchTerm): Boolean {
        val normalized = term.normalizedOrNull() ?: return false
        if (!prepareModeForPostTerm(post, normalized)) return false
        addExcludeTerm(normalized)
        return normalized in draftQuery.excludeTerms
    }

    fun addIncludeSuggestion(suggestion: FacetedTagSuggestion): Boolean {
        return addIncludeTerm(suggestion.toSearchTerm())
    }

    fun addExcludeSuggestion(suggestion: FacetedTagSuggestion): Boolean {
        return addExcludeTerm(suggestion.toSearchTerm())
    }

    fun addSuggestion(
        suggestion: FacetedTagSuggestion,
        excluded: Boolean = false,
    ): Boolean {
        return if (excluded) addExcludeSuggestion(suggestion) else addIncludeSuggestion(suggestion)
    }

    fun removeIncludeTag(tag: String) {
        draftQuery = draftQuery.copy(
            includeTerms = draftQuery.includeTerms.filterNot { term ->
                term.isPortableGeneralTag && term.value == tag
            },
        )
    }

    fun removeExcludeTag(tag: String) {
        draftQuery = draftQuery.copy(
            excludeTerms = draftQuery.excludeTerms.filterNot { term ->
                term.isPortableGeneralTag && term.value == tag
            },
        )
    }

    fun removeIncludeTerm(term: SearchTerm) {
        draftQuery = draftQuery.copy(
            includeTerms = draftQuery.includeTerms.filterNot { candidate -> candidate == term },
        )
    }

    fun removeExcludeTerm(term: SearchTerm) {
        draftQuery = draftQuery.copy(
            excludeTerms = draftQuery.excludeTerms.filterNot { candidate -> candidate == term },
        )
    }

    fun selectSearchScope(scope: FacetedSearchScope): Boolean {
        if (scope !in supportedSearchScopes) return false
        if (selectedSearchScope == scope) return true
        selectedSearchScope = scope
        clearAutocompleteSuggestions()
        tagInputValidationMessage = null
        return true
    }

    fun setMode(mode: QueryMode) {
        val hadSourceOwnedTerms = draftQuery.hasSourceOwnedTerms()
        val resolvedMode = when {
            isModeAvailable(mode) -> mode
            else -> QueryMode.Unified
        }
        val restored = appliedByMode[modeKey(resolvedMode)] ?: defaultQuery(resolvedMode)
        val sanitized = restored.copy(mode = resolvedMode).forMode(resolvedMode)
        draftQuery = sanitized.query
        resetUnsupportedSearchScope()
        clearTagInputUiState()
        if (
            resolvedMode == QueryMode.Unified &&
            (hadSourceOwnedTerms || sanitized.removedSourceOwnedTerms)
        ) {
            tagInputValidationMessage = UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE
        }
    }

    fun setSort(sort: SortMode) {
        draftQuery = draftQuery.copy(sort = sort)
    }

    fun resetDraft() {
        draftQuery = appliedQuery
        resetUnsupportedSearchScope()
        clearTagInputUiState()
    }

    fun clearDraft() {
        val mode = draftQuery.mode.takeIf(::isModeAvailable) ?: QueryMode.Unified
        draftQuery = defaultQuery(mode)
        resetUnsupportedSearchScope()
        clearTagInputUiState()
    }

    fun prepareTagSearch(
        includeTags: List<String>,
        excludeTags: List<String> = emptyList(),
        mode: QueryMode = QueryMode.Unified,
    ): Boolean {
        val normalizedInclude = includeTags
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val normalizedExclude = excludeTags
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it in normalizedInclude }
            .distinct()
        if (normalizedInclude.isEmpty() && normalizedExclude.isEmpty()) return false
        if (!isModeAvailable(mode)) return false
        draftQuery = defaultQuery(mode).copy(
            includeTerms = normalizedInclude.map { value -> SearchTerm(value = value) },
            excludeTerms = normalizedExclude.map { value -> SearchTerm(value = value) },
        )
        clearSearchResultsForRetry()
        statuses = emptyList()
        errorMessage = null
        clearTagInputUiState()
        return true
    }

    fun prepareTagSearch(tag: String): Boolean {
        return prepareTagSearch(includeTags = listOf(tag))
    }

    fun setDateRangePreset(preset: DateRangePreset) {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L
        val dateRange = when (preset) {
            DateRangePreset.NONE -> null
            DateRangePreset.TODAY -> DateRange(fromEpochMs = now - dayMs, toEpochMs = now)
            DateRangePreset.LAST_7_DAYS -> DateRange(fromEpochMs = now - 7L * dayMs, toEpochMs = now)
            DateRangePreset.LAST_30_DAYS -> DateRange(fromEpochMs = now - 30L * dayMs, toEpochMs = now)
        }
        draftQuery = draftQuery.copy(dateRange = dateRange)
    }

    fun setMinScore(minScore: Int?) {
        draftQuery = draftQuery.copy(minScore = minScore)
    }

    fun selectedNhentaiLanguageFilter(): NhentaiLanguageFilter {
        val match = draftQuery.includeTerms.firstNotNullOfOrNull { term ->
            term.nhentaiLanguageFilterOrNull()
        }
        return match ?: NhentaiLanguageFilter.ANY
    }

    fun setNhentaiLanguageFilter(filter: NhentaiLanguageFilter) {
        val cleaned = draftQuery.includeTerms.filterNot { term ->
            term.nhentaiLanguageFilterOrNull() != null
        }
        val languageTag = NHENTAI_LANGUAGE_TAG_BY_FILTER[filter]
        val languageTerm = languageTag?.let { value ->
            SearchTerm(
                value = value,
                facet = SearchFacet.LANGUAGE,
                sourceNamespace = NHENTAI_LANGUAGE_NAMESPACE,
            )
        }
        val nextInclude = if (languageTerm == null || languageTerm in cleaned) {
            cleaned
        } else {
            cleaned + languageTerm
        }
        draftQuery = draftQuery.copy(includeTerms = nextInclude)
    }

    fun selectedNhentaiFullColorFilter(): Boolean {
        return draftQuery.includeTerms.any { term ->
            term.isNhentaiFullColorFilter()
        }
    }

    fun setNhentaiFullColorFilter(enabled: Boolean) {
        val cleaned = draftQuery.includeTerms.filterNot { term ->
            term.isNhentaiFullColorFilter()
        }
        val nextInclude = if (enabled) {
            cleaned + SearchTerm(
                value = NHENTAI_FULL_COLOR_TAG,
                facet = SearchFacet.TAG,
                sourceNamespace = NHENTAI_TAG_NAMESPACE,
            )
        } else {
            cleaned
        }
        draftQuery = draftQuery.copy(includeTerms = nextInclude)
    }

    fun directNhentaiGalleryIdCandidate(query: Query = draftQuery): String? {
        return query.directNhentaiGalleryIdCandidate()
    }

    suspend fun resolveNhentaiGalleryById(galleryId: String): Post? {
        val normalizedId = galleryId.trim().takeIf(String::isDigitsOnly) ?: return null
        val adapter = registry.adapterFor(SourceKey.NHENTAI) ?: return null
        return adapter.resolvePost(PostId(source = SourceKey.NHENTAI, sourcePostId = normalizedId))
    }

    fun resetFilters() {
        draftQuery = draftQuery.copy(
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    suspend fun refreshAutocompleteSuggestions(input: String) {
        val parsedInput = parseScopedInput(input)
        val typedPrefix = parsedInput.value
        if (typedPrefix.isBlank()) {
            val explicitScope = parsedInput.explicitScope
            if (explicitScope != null) {
                when (draftQuery.mode) {
                    QueryMode.Unified -> {
                        tagInputValidationMessage = UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE
                    }

                    is QueryMode.Source -> {
                        val resolvedScope = resolveSupportedScope(
                            explicitScope,
                            supportedSearchScopes,
                        )
                        if (resolvedScope == null) {
                            tagInputValidationMessage = UNSUPPORTED_SEARCH_SCOPE_MESSAGE
                        } else {
                            selectedSearchScope = resolvedScope
                            tagInputValidationMessage = null
                        }
                    }
                }
            }
            refreshFeaturedFacetedSuggestions()
            return
        }

        val explicitScope = parsedInput.explicitScope
        if (explicitScope != null) {
            when (draftQuery.mode) {
                QueryMode.Unified -> {
                    clearAutocompleteSuggestions()
                    tagInputValidationMessage = UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE
                    return
                }

                is QueryMode.Source -> {
                    val resolvedScope = resolveSupportedScope(explicitScope, supportedSearchScopes)
                    if (resolvedScope == null) {
                        clearAutocompleteSuggestions()
                        tagInputValidationMessage = UNSUPPORTED_SEARCH_SCOPE_MESSAGE
                        return
                    }
                    selectedSearchScope = resolvedScope
                }
            }
        }

        autocompleteSuggestions = when (val mode = draftQuery.mode) {
            QueryMode.Unified -> {
                val enabledSources = effectiveEnabledSources()
                val fetched = enabledSources
                    .flatMap { source ->
                        val sourcePrefix = autocompletePrefixForSource(source, typedPrefix)
                        val adapter = registry.adapterFor(source)
                        if (adapter is FacetedSearchSourceAdapter) {
                            val allScope = FacetedSearchScope.All.takeIf { scope ->
                                scope in adapter.supportedSearchScopes
                            } ?: adapter.supportedSearchScopes.firstOrNull { scope ->
                                scope.facet == SearchFacet.TAG &&
                                    scope.sourceNamespace in setOf(null, "tag")
                            }
                            if (allScope == null) return@flatMap emptyList()
                            val faceted = try {
                                adapter.autocompleteFaceted(
                                    prefix = sourcePrefix,
                                    scope = allScope,
                                    limit = FACETED_AUTOCOMPLETE_LIMIT,
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                emptyList()
                            }
                            if (faceted.isNotEmpty()) {
                                tagSuggestionStore.putFaceted(source, faceted)
                            }
                            faceted
                                .filter(FacetedTagSuggestion::isPortableTagSuggestion)
                                .map(FacetedTagSuggestion::toPortableLegacySuggestion)
                        } else {
                            val suggestions = try {
                                adapter?.autocompleteTags(prefix = sourcePrefix, limit = 10).orEmpty()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                emptyList()
                            }
                            if (suggestions.isNotEmpty()) {
                                tagSuggestionStore.put(source, suggestions)
                            }
                            suggestions.filter(TagSuggestion::isPortableTagSuggestion)
                        }
                    }
                if (fetched.isNotEmpty()) {
                    rankSuggestionsByPrefix(fetched, prefix = typedPrefix, limit = 20)
                } else {
                    val cached = enabledSources
                        .flatMap { source -> tagSuggestionStore.get(source, limit = 120) }
                    rankSuggestionsByPrefix(cached, prefix = typedPrefix, limit = 20)
                }
            }

            is QueryMode.Source -> {
                val facetedAdapter = registry.adapterFor(mode.source) as? FacetedSearchSourceAdapter
                if (facetedAdapter != null) {
                    val requestedScope = explicitScope
                        ?.let { prefix -> resolveSupportedScope(prefix, supportedSearchScopes) }
                        ?: selectedSearchScope.takeIf { scope -> scope in supportedSearchScopes }
                        ?: FacetedSearchScope.All.takeIf { scope -> scope in supportedSearchScopes }
                    if (requestedScope == null) {
                        clearAutocompleteSuggestions()
                        tagInputValidationMessage = UNSUPPORTED_SEARCH_SCOPE_MESSAGE
                        return
                    }
                    selectedSearchScope = requestedScope
                    val fetched = try {
                        facetedAdapter.autocompleteFaceted(
                            prefix = typedPrefix,
                            scope = requestedScope,
                            limit = FACETED_AUTOCOMPLETE_LIMIT,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    if (fetched.isNotEmpty()) {
                        tagSuggestionStore.putFaceted(mode.source, fetched)
                    }
                    val candidates = if (fetched.isNotEmpty()) {
                        fetched
                    } else {
                        tagSuggestionStore.getFaceted(
                            source = mode.source,
                            scope = requestedScope,
                            limit = FACETED_AUTOCOMPLETE_CACHE_LIMIT,
                        )
                    }
                    val ranked = rankFacetedSuggestionsByPrefix(
                        suggestions = candidates,
                        prefix = typedPrefix,
                        limit = FACETED_AUTOCOMPLETE_LIMIT,
                    )
                    facetedAutocompleteSuggestions = ranked
                    tagInputValidationMessage = null
                    ranked.map(FacetedTagSuggestion::toLegacySuggestion)
                } else {
                    facetedAutocompleteSuggestions = emptyList()
                    val sourcePrefix = autocompletePrefixForSource(mode.source, typedPrefix)
                    val fetched = runCatching {
                        registry.adapterFor(mode.source)?.autocompleteTags(prefix = sourcePrefix, limit = 20).orEmpty()
                    }.getOrDefault(emptyList())
                    if (fetched.isNotEmpty()) {
                        tagSuggestionStore.put(mode.source, fetched)
                        rankSuggestionsByPrefix(fetched, prefix = typedPrefix, limit = 20)
                    } else {
                        val cached = tagSuggestionStore.get(mode.source, limit = 120)
                        val fallback = if (cached.isNotEmpty()) cached else trendingTags
                        rankSuggestionsByPrefix(fallback, prefix = typedPrefix, limit = 20)
                    }
                }
            }
        }
        if (draftQuery.mode == QueryMode.Unified) {
            facetedAutocompleteSuggestions = emptyList()
        }
    }

    suspend fun refreshFeaturedFacetedSuggestions() {
        val mode = draftQuery.mode as? QueryMode.Source
        val adapter = mode?.let { registry.adapterFor(it.source) as? FacetedSearchSourceAdapter }
        val scope = selectedSearchScope.takeIf { candidate ->
            !candidate.isAll && candidate in supportedSearchScopes
        }
        if (mode == null || adapter == null || scope == null) {
            clearAutocompleteSuggestions()
            return
        }
        val featured = try {
            adapter.featuredFacetedSuggestions(scope, FACETED_AUTOCOMPLETE_LIMIT)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
        if (featured.isNotEmpty()) tagSuggestionStore.putFaceted(mode.source, featured)
        val suggestions = featured.ifEmpty {
            tagSuggestionStore.getFaceted(
                source = mode.source,
                scope = scope,
                limit = FACETED_AUTOCOMPLETE_LIMIT,
            )
        }
        facetedAutocompleteSuggestions = suggestions
        autocompleteSuggestions = suggestions.map(FacetedTagSuggestion::toLegacySuggestion)
    }

    suspend fun loadTrendingTags(forceRefresh: Boolean = false) {
        errorMessage = null
        val now = clock()
        when (val mode = draftQuery.mode) {
            QueryMode.Unified -> {
                val enabled = effectiveEnabledSources()
                if (enabled.isEmpty()) {
                    trendingTags = emptyList()
                    return
                }

                val cachedBySource = enabled.associateWith { source ->
                    tagSuggestionStore.get(source, limit = TRENDING_PER_SOURCE_CACHE_LIMIT)
                }
                trendingTags = rankTrendingSuggestions(
                    suggestions = cachedBySource.values.flatten(),
                    limit = UNIFIED_TRENDING_LIMIT,
                )

                val sourcesToRefresh = enabled.filter { source ->
                    shouldRefreshTrending(
                        source = source,
                        nowEpochMs = now,
                        forceRefresh = forceRefresh,
                        cached = cachedBySource[source].orEmpty(),
                    )
                }
                if (sourcesToRefresh.isEmpty()) {
                    return
                }

                var refreshedAny = false
                sourcesToRefresh.forEach { source ->
                    val fetched = fetchTrendingForSource(source = source, limit = TRENDING_FETCH_PER_SOURCE_LIMIT)
                    if (fetched.isNotEmpty()) {
                        refreshedAny = true
                    }
                }
                if (refreshedAny || trendingTags.isEmpty()) {
                    val refreshed = enabled.flatMap { source ->
                        tagSuggestionStore.get(source, limit = TRENDING_PER_SOURCE_CACHE_LIMIT)
                    }
                    trendingTags = rankTrendingSuggestions(
                        suggestions = refreshed,
                        limit = UNIFIED_TRENDING_LIMIT,
                    )
                }
            }

            is QueryMode.Source -> {
                val source = mode.source
                val cached = tagSuggestionStore.get(source, limit = SOURCE_TRENDING_LIMIT)
                trendingTags = rankTrendingSuggestions(cached, limit = SOURCE_TRENDING_LIMIT)

                if (!shouldRefreshTrending(source, now, forceRefresh, cached)) {
                    return
                }

                val fetched = fetchTrendingForSource(source = source, limit = SOURCE_TRENDING_LIMIT)
                if (fetched.isNotEmpty() || trendingTags.isEmpty()) {
                    val refreshed = tagSuggestionStore.get(source, limit = SOURCE_TRENDING_LIMIT)
                    trendingTags = rankTrendingSuggestions(refreshed, limit = SOURCE_TRENDING_LIMIT)
                }
            }
        }
    }

    private fun shouldRefreshTrending(
        source: SourceKey,
        nowEpochMs: Long,
        forceRefresh: Boolean,
        cached: List<TagSuggestion>,
    ): Boolean {
        if (forceRefresh) return true
        if (cached.isEmpty()) return true
        val lastRefresh = lastTrendingRefreshAtBySource[source] ?: return true
        return nowEpochMs - lastRefresh >= TRENDING_REFRESH_INTERVAL_MS
    }

    private suspend fun fetchTrendingForSource(source: SourceKey, limit: Int): List<TagSuggestion> {
        val fetched = runCatching {
            registry.adapterFor(source)?.trendingTags(limit = limit).orEmpty()
        }.getOrDefault(emptyList())
        lastTrendingRefreshAtBySource[source] = clock()
        if (fetched.isNotEmpty()) {
            tagSuggestionStore.put(source, fetched)
        }
        return fetched
    }

    private fun rankTrendingSuggestions(
        suggestions: List<TagSuggestion>,
        limit: Int,
    ): List<TagSuggestion> {
        if (limit <= 0) return emptyList()
        return suggestions
            .asSequence()
            .filter { suggestion -> suggestion.text.isNotBlank() }
            .distinctBy { suggestion -> normalizeMatchToken(suggestion.text) }
            .sortedWith(
                compareByDescending<TagSuggestion> { suggestion -> suggestion.count ?: Int.MIN_VALUE }
                    .thenBy { suggestion -> suggestion.text.lowercase() }
            )
            .take(limit)
            .toList()
    }

    suspend fun applyDraft() {
        val sanitized = draftQuery.forMode(draftQuery.mode)
        draftQuery = sanitized.query
        if (sanitized.removedSourceOwnedTerms) {
            tagInputValidationMessage = UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE
        }
        appliedQuery = draftQuery
        appliedByMode[modeKey(appliedQuery.mode)] = appliedQuery
        queryRepository.upsertAppliedQuery(modeKey(appliedQuery.mode), appliedQuery)
        queryRepository.upsertAppliedQuery(LAST_ACTIVE_QUERY_KEY, appliedQuery)
        val hash = appliedQueryHash
        uiRestoreRepository.setSearchScrollState(
            queryHash = hash,
            state = SearchScrollState(
                firstVisibleItemIndex = 0,
                firstVisibleItemOffsetPx = 0,
            ),
        )
        queryRepository.upsertScrollOffset(hash, 0)
        recentsRepository.recordSearch(appliedQuery, hash)
        executeSearch()
    }

    suspend fun applyHistoricalQuery(query: Query): Boolean {
        if (!isModeAvailable(query.mode)) return false
        val sanitized = query.forMode(query.mode)
        draftQuery = sanitized.query
        resetUnsupportedSearchScope()
        clearTagInputUiState()
        if (sanitized.removedSourceOwnedTerms) {
            tagInputValidationMessage = UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE
        }
        applyDraft()
        return true
    }

    suspend fun retry() {
        executeSearch()
    }

    suspend fun restoreLastAppliedSearchIfNeeded() {
        if (!hasExecutedSearch) return
        if (hasPendingChanges) return
        if (loading || loadingMore || results.isNotEmpty()) return
        executeSearch()
    }

    suspend fun loadNextPage() {
        if (loading || loadingMore || !canLoadMore) return

        loadingMore = true
        errorMessage = null
        try {
            when (val mode = appliedQuery.mode) {
                QueryMode.Unified -> {
                    val enabledSources = effectiveEnabledSources()
                    val disabledStatuses = availableSources
                        .filterNot { it in enabledSources }
                        .map { source ->
                            SourceRunStatus(
                                source = source,
                                state = SourceRunState.EXCLUDED,
                                errorMessage = "Disabled in settings",
                            )
                        }
                    if (enabledSources.isEmpty()) {
                        canLoadMore = false
                        statuses = disabledStatuses
                        return
                    }

                    val pageableSources = enabledSources.filterTo(mutableSetOf()) { source ->
                        !unifiedNextPageTokens[source].isNullOrBlank()
                    }
                    if (pageableSources.isEmpty()) {
                        canLoadMore = false
                        return
                    }

                    val result = registry.unifiedOrchestrator().search(
                        query = appliedQuery,
                        enabledSources = pageableSources,
                        pageTokens = unifiedNextPageTokens.filterKeys { it in pageableSources },
                        weights = effectiveWeights(pageableSources),
                        queryOverridesBySource = unifiedQueryOverrides.filterKeys { it in pageableSources },
                    )
                    results = mergeResults(results, result.items)
                    rememberSeenTags(result.items)
                    statuses = (result.statuses + disabledStatuses)
                        .distinctBy { it.source }
                        .sortedBy { it.source.name }
                    unifiedNextPageTokens = unifiedNextPageTokens.toMutableMap().apply {
                        putAll(result.nextPageTokens)
                    }
                    canLoadMore = unifiedNextPageTokens.values.any { !it.isNullOrBlank() }
                    maybeHandlePixivUnknownFailure()
                }

                is QueryMode.Source -> {
                    val token = sourceNextPageToken
                    if (token.isNullOrBlank()) {
                        canLoadMore = false
                        return
                    }
                    val adapter = requireNotNull(registry.adapterFor(mode.source)) {
                        "No adapter for ${mode.source}"
                    }
                    val page = adapter.search(appliedQuery, pageToken = token)
                    results = mergeResults(results, page.items)
                    rememberSeenTags(page.items)
                    statuses = listOf(SourceRunStatus(mode.source, state = SourceRunState.SUCCESS))
                    sourceNextPageToken = page.nextPageToken
                    canLoadMore = !sourceNextPageToken.isNullOrBlank()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            errorMessage = if (isPixivUnknownError(error)) {
                PIXIV_UNKNOWN_RETRY_MESSAGE
            } else {
                error.message ?: "Could not load more results"
            }
            canLoadMore = false
        } finally {
            loadingMore = false
        }
    }

    suspend fun persistSearchScrollState(index: Int, offsetPx: Int) {
        val hash = appliedQueryHash
        uiRestoreRepository.setSearchScrollState(
            queryHash = hash,
            state = SearchScrollState(
                firstVisibleItemIndex = index,
                firstVisibleItemOffsetPx = offsetPx,
            ),
        )
        queryRepository.upsertScrollOffset(hash, offsetPx)
    }

    suspend fun restoreSearchScrollState(): SearchScrollState? {
        val hash = appliedQueryHash
        return uiRestoreRepository.getSearchScrollState(hash)
            ?: queryRepository.getScrollOffset(hash)?.let { offset ->
                SearchScrollState(firstVisibleItemIndex = 0, firstVisibleItemOffsetPx = offset)
            }
    }

    fun buildViewerLaunchContext(
        startIndex: Int,
        scrollOffsetHint: Int,
    ): ViewerLaunchContext {
        return ViewerLaunchContext(
            queryHash = appliedQueryHash,
            startIndex = startIndex,
            streamSource = ViewerStreamSource.SEARCH,
            scrollOffsetHint = scrollOffsetHint,
        )
    }

    suspend fun setViewerLaunchContext(context: ViewerLaunchContext?) {
        uiRestoreRepository.setViewerLaunchContext(context)
    }

    fun displayResults(): List<Post> {
        return results.map(::displayPost)
    }

    fun displayPost(post: Post): Post {
        return resolvedPostOverridesByQueryHash[appliedQueryHash]?.get(post.id) ?: post
    }

    suspend fun resolvePost(postId: PostId): Post? {
        val adapter = registry.adapterFor(postId.source) ?: return null
        return adapter.resolvePost(postId)
    }

    suspend fun recoverPostMedia(post: Post, failedMedia: ImageRef): Post? {
        val recovered = recoverRemoteMedia(registry, post, failedMedia) ?: return null
        rememberResolvedPost(recovered)
        return recovered
    }

    suspend fun resolvePostForSearch(postId: PostId): Post? {
        val existing = resolvedPostOverridesByQueryHash[appliedQueryHash]?.get(postId)
        if (existing != null) return existing
        if (shouldDeferResolve(postId)) return null

        val adapter = registry.adapterFor(postId.source) ?: return null
        return try {
            val resolved = adapter.resolvePost(postId) ?: return null
            rememberResolvedPost(resolved)
            resolved
        } catch (error: SourceAdapterException) {
            if (error.reason == SourceFailureReason.RATE_LIMITED) {
                rememberResolveFailure(postId, error.reason)
                null
            } else {
                throw error
            }
        }
    }

    fun rememberResolvedPost(post: Post) {
        val queryHash = appliedQueryHash
        val bucket = resolvedPostOverridesByQueryHash.getOrPut(queryHash) { linkedMapOf() }
        bucket.remove(post.id)
        bucket[post.id] = post
        trimResolvedOverrides(bucket)
        recentResolveFailuresByQueryHash[queryHash]?.remove(post.id)
        displayResultsVersion += 1
    }

    fun shouldDeferResolve(postId: PostId): Boolean {
        val now = clock()
        val record = recentResolveFailuresByQueryHash[appliedQueryHash]?.get(postId) ?: return false
        return record.reason == SourceFailureReason.RATE_LIMITED && now < record.backoffUntilMs
    }

    private suspend fun executeSearch() {
        val previousResults = results
        val previousStatuses = statuses
        loading = true
        loadingMore = false
        canLoadMore = false
        unifiedNextPageTokens = emptyMap()
        unifiedQueryOverrides = emptyMap()
        sourceNextPageToken = null
        errorMessage = null
        statuses = emptyList()

        try {
            when (val mode = appliedQuery.mode) {
                QueryMode.Unified -> {
                    val enabledSources = effectiveEnabledSources()
                    val disabledStatuses = availableSources
                        .filterNot { it in enabledSources }
                        .map { source ->
                            SourceRunStatus(
                                source = source,
                                state = SourceRunState.EXCLUDED,
                                errorMessage = "Disabled in settings",
                            )
                        }
                    if (enabledSources.isEmpty()) {
                        results = emptyList()
                        statuses = disabledStatuses
                        return
                    }

                    unifiedQueryOverrides = buildUnifiedQueryOverrides(
                        query = appliedQuery,
                        enabledSources = enabledSources,
                    )
                    val result = registry.unifiedOrchestrator().search(
                        query = appliedQuery,
                        enabledSources = enabledSources,
                        pageTokens = emptyMap(),
                        weights = effectiveWeights(enabledSources),
                        queryOverridesBySource = unifiedQueryOverrides,
                    )
                    results = result.items
                    rememberSeenTags(result.items)
                    statuses = (result.statuses + disabledStatuses)
                        .distinctBy { it.source }
                        .sortedBy { it.source.name }
                    unifiedNextPageTokens = result.nextPageTokens
                    canLoadMore = unifiedNextPageTokens.values.any { !it.isNullOrBlank() }
                    maybeHandlePixivUnknownFailure()
                }

                is QueryMode.Source -> {
                    if (!isModeAvailable(mode)) {
                        results = emptyList()
                        statuses = listOf(
                            SourceRunStatus(
                                source = mode.source,
                                state = SourceRunState.EXCLUDED,
                                errorMessage = "Source not available in this build",
                            )
                        )
                        return
                    }
                    val adapter = requireNotNull(registry.adapterFor(mode.source)) {
                        "No adapter for ${mode.source}"
                    }
                    val page = adapter.search(appliedQuery, pageToken = null)
                    results = page.items
                    rememberSeenTags(page.items)
                    statuses = listOf(SourceRunStatus(mode.source, state = SourceRunState.SUCCESS))
                    sourceNextPageToken = page.nextPageToken
                    canLoadMore = !sourceNextPageToken.isNullOrBlank()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isPixivUnknownError(error)) {
                clearSearchResultsForRetry()
                errorMessage = PIXIV_UNKNOWN_RETRY_MESSAGE
            } else {
                results = previousResults
                statuses = previousStatuses
                errorMessage = error.message ?: "Unknown error"
                canLoadMore = false
            }
        } finally {
            hasExecutedSearch = true
            loading = false
        }
    }

    private fun maybeHandlePixivUnknownFailure() {
        if (results.isNotEmpty()) return
        val hasPixivUnknownFailure = statuses.any { status ->
            status.source == SourceKey.PIXIV &&
                status.state == SourceRunState.FAILED &&
                (
                    status.failureReason == SourceFailureReason.UNKNOWN ||
                        status.errorMessage?.contains("PIXIV_UNKNOWN", ignoreCase = true) == true
                    )
        }
        if (hasPixivUnknownFailure) {
            clearSearchResultsForRetry()
            errorMessage = PIXIV_UNKNOWN_RETRY_MESSAGE
        }
    }

    private fun clearSearchResultsForRetry() {
        results = emptyList()
        canLoadMore = false
        unifiedNextPageTokens = emptyMap()
        unifiedQueryOverrides = emptyMap()
        sourceNextPageToken = null
    }

    private suspend fun buildUnifiedQueryOverrides(
        query: Query,
        enabledSources: Set<SourceKey>,
    ): Map<SourceKey, Query> {
        if (query.mode != QueryMode.Unified) return emptyMap()
        val overrides = mutableMapOf<SourceKey, Query>()
        val portableIncludeTerms = query.includeTerms.filter(SearchTerm::isPortableGeneralTag)
        val portableExcludeTerms = query.excludeTerms.filter(SearchTerm::isPortableGeneralTag)

        if (SourceKey.GELBOORU in enabledSources) {
            val gelbooruAdapter = registry.adapterFor(SourceKey.GELBOORU)
            if (gelbooruAdapter != null) {
                val includeTags = resolveGelbooruCompatibilityTags(
                    gelbooruAdapter,
                    portableIncludeTerms.map(SearchTerm::value),
                )
                val excludeTags = resolveGelbooruCompatibilityTags(
                    gelbooruAdapter,
                    portableExcludeTerms.map(SearchTerm::value),
                )
                overrides[SourceKey.GELBOORU] = query.copy(
                    includeTerms = includeTags.map { value -> SearchTerm(value = value) },
                    excludeTerms = excludeTags.map { value -> SearchTerm(value = value) },
                )
            }
        }

        if (SourceKey.PIXIV in enabledSources) {
            overrides[SourceKey.PIXIV] = query.copy(
                includeTerms = resolvePixivCompatibilityTags(portableIncludeTerms.map(SearchTerm::value))
                    .map { value -> SearchTerm(value = value) },
                excludeTerms = resolvePixivCompatibilityTags(portableExcludeTerms.map(SearchTerm::value))
                    .map { value -> SearchTerm(value = value) },
            )
        }

        return overrides
    }

    private suspend fun resolveGelbooruCompatibilityTags(
        adapter: SourceAdapter,
        tags: List<String>,
    ): List<String> {
        if (tags.isEmpty()) return emptyList()

        val cache = mutableMapOf<String, String>()
        val resolved = mutableListOf<String>()
        tags.forEach { raw ->
            val normalized = raw.trim()
            if (normalized.isBlank()) return@forEach
            val key = normalized.lowercase()
            val mapped = cache.getOrPut(key) {
                val sourcePrefix = autocompletePrefixForSource(SourceKey.GELBOORU, normalized)
                val suggestions = runCatching {
                    adapter.autocompleteTags(prefix = sourcePrefix, limit = 1)
                }.getOrDefault(emptyList())
                if (suggestions.isNotEmpty()) {
                    tagSuggestionStore.put(SourceKey.GELBOORU, suggestions)
                }
                suggestions.firstOrNull()?.text?.trim().takeUnless { it.isNullOrBlank() } ?: normalized
            }
            if (mapped !in resolved) {
                resolved += mapped
            }
        }
        return resolved
    }

    private fun resolvePixivCompatibilityTags(tags: List<String>): List<String> {
        if (tags.isEmpty()) return emptyList()

        val resolved = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        tags.forEach { raw ->
            val mapped = normalizePixivCompatibilityToken(raw)
            if (mapped.isBlank()) return@forEach

            val dedupeKey = normalizeMatchToken(mapped)
            if (seen.add(dedupeKey)) {
                resolved += mapped
            }
        }
        return resolved
    }

    private fun normalizePixivCompatibilityToken(raw: String): String {
        var normalized = raw
            .trim()
            .removePrefix("-")
            .replace('_', ' ')
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        while (normalized.isNotBlank() && PIXIV_TRAILING_PARENTHESIS_REGEX.containsMatchIn(normalized)) {
            normalized = normalized.replace(PIXIV_TRAILING_PARENTHESIS_REGEX, "").trim()
        }
        return normalized
    }

    private fun isPixivUnknownError(error: Throwable): Boolean {
        if (error is SourceAdapterException && error.reason == SourceFailureReason.UNKNOWN) {
            return when (val mode = appliedQuery.mode) {
                QueryMode.Unified -> true
                is QueryMode.Source -> mode.source == SourceKey.PIXIV
            }
        }
        return error.message
            .orEmpty()
            .contains("PIXIV_UNKNOWN", ignoreCase = true)
    }

    private fun mergeResults(
        current: List<Post>,
        next: List<Post>,
    ): List<Post> {
        if (next.isEmpty()) return current
        if (current.isEmpty()) return next

        val seen = current
            .mapTo(mutableSetOf()) { "${it.id.source.name}:${it.id.sourcePostId}" }
        val merged = current.toMutableList()
        next.forEach { post ->
            val key = "${post.id.source.name}:${post.id.sourcePostId}"
            if (seen.add(key)) {
                merged += post
            }
        }
        return merged
    }

    private fun rememberSeenTags(posts: List<Post>) {
        if (posts.isEmpty()) return

        posts
            .groupBy { post -> post.id.source }
            .forEach { (source, sourcePosts) ->
                val seenSuggestions = sourcePosts
                    .asSequence()
                    .flatMap { post ->
                        recommendationTaxonomyFor(post)
                            .asSequence()
                            .map { term ->
                                FacetedTagSuggestion(
                                    text = normalizeStoredTagForSource(source, term.value),
                                    facet = SearchFacet.TAG,
                                    sourceNamespace = term.sourceNamespace,
                                    count = null,
                                )
                            }
                    }
                    .filter { suggestion -> suggestion.text.isNotBlank() }
                    .distinctBy { suggestion ->
                        Triple(
                            suggestion.facet,
                            suggestion.sourceNamespace,
                            sourceTagKey(source, suggestion.text),
                        )
                    }
                    .take(SEEN_TAGS_PER_SOURCE_INGEST_LIMIT)
                    .toList()
                if (seenSuggestions.isNotEmpty()) {
                    tagSuggestionStore.putFaceted(source, seenSuggestions)
                }
            }
    }

    private fun modeKey(mode: QueryMode): String {
        return when (mode) {
            QueryMode.Unified -> "unified"
            is QueryMode.Source -> "source:${mode.source.name}"
        }
    }

    private fun isModeAvailable(mode: QueryMode): Boolean {
        return when (mode) {
            QueryMode.Unified -> true
            is QueryMode.Source -> mode.source in registry.availableSources()
        }
    }

    private fun effectiveEnabledSources(): Set<SourceKey> {
        return runtimeSettings.runtime.enabledSources.intersect(registry.availableSources())
    }

    private fun rememberResolveFailure(postId: PostId, reason: SourceFailureReason) {
        val now = clock()
        val queryHash = appliedQueryHash
        val bucket = recentResolveFailuresByQueryHash.getOrPut(queryHash) { linkedMapOf() }
        val previous = bucket[postId]
        val backoffMs = if (
            previous != null &&
            previous.reason == SourceFailureReason.RATE_LIMITED &&
            reason == SourceFailureReason.RATE_LIMITED &&
            now - previous.lastFailureAtMs <= RATE_LIMIT_REPEAT_WINDOW_MS
        ) {
            RATE_LIMIT_BACKOFF_REPEAT_MS
        } else {
            RATE_LIMIT_BACKOFF_FIRST_MS
        }
        bucket[postId] = ResolveFailureRecord(
            lastFailureAtMs = now,
            backoffUntilMs = now + backoffMs,
            reason = reason,
        )
        trimResolveFailures(bucket)
    }

    private fun trimResolvedOverrides(bucket: LinkedHashMap<PostId, Post>) {
        val maxEntries = results.size
            .takeIf { it > 0 }
            ?.coerceAtMost(MAX_RESOLVED_POST_OVERRIDES_PER_QUERY)
            ?: MAX_RESOLVED_POST_OVERRIDES_PER_QUERY
        while (bucket.size > maxEntries) {
            val eldest = bucket.entries.firstOrNull()?.key ?: break
            bucket.remove(eldest)
        }
        while (resolvedPostOverridesByQueryHash.size > MAX_REMEMBERED_QUERY_OVERRIDES) {
            val eldestQuery = resolvedPostOverridesByQueryHash.entries.firstOrNull()?.key ?: break
            resolvedPostOverridesByQueryHash.remove(eldestQuery)
        }
    }

    private fun trimResolveFailures(bucket: MutableMap<PostId, ResolveFailureRecord>) {
        while (bucket.size > MAX_RESOLVED_POST_OVERRIDES_PER_QUERY) {
            val eldest = bucket.entries.firstOrNull()?.key ?: break
            bucket.remove(eldest)
        }
    }

    private fun requiresGelbooruSuggestionSelection(): Boolean {
        return draftQuery.mode == QueryMode.Source(SourceKey.GELBOORU)
    }

    private fun normalizeTypedTag(input: String): String {
        return parseScopedInput(input).value
    }

    private fun autocompletePrefixForSource(source: SourceKey, input: String): String {
        val normalized = normalizeTypedTag(input)
        return when (source) {
            SourceKey.GELBOORU, SourceKey.IWARA, SourceKey.RULE34XXX -> normalizeGelbooruToken(normalized)
            else -> normalized
        }
    }

    private fun resolveCommittedTagInput(input: String): String {
        val trimmed = input.trim()
        val source = (draftQuery.mode as? QueryMode.Source)?.source ?: return trimmed
        if (registry.adapterFor(source) is FacetedSearchSourceAdapter) return trimmed
        if (source !in SUGGESTION_CANONICALIZATION_SOURCES) return trimmed

        val isExclude = trimmed.startsWith("-")
        val typedTag = normalizeTypedTag(trimmed)
        val matched = autocompleteSuggestions
            .firstOrNull { suggestion -> tagsMatchForSource(source, suggestion.text, typedTag) }
            ?.text
            ?.trim()
            .orEmpty()
            .ifBlank { typedTag }
        return if (isExclude) "-$matched" else matched
    }

    private fun tagsMatchForSource(source: SourceKey, suggestionText: String, typedTag: String): Boolean {
        return sourceTagsMatch(source, suggestionText, typedTag)
    }

    private fun normalizeStoredTagForSource(source: SourceKey, value: String): String {
        return normalizeFavoriteTagForStorage(source, value)
    }

    private fun isSuggestedTag(tag: String): Boolean {
        return autocompleteSuggestions.any { suggestion ->
            tagsMatchForSource(SourceKey.GELBOORU, suggestion.text, tag)
        }
    }

    private fun canUseParsedScope(input: ParsedScopedInput): Boolean {
        val prefix = input.explicitScope ?: return true
        if (draftQuery.mode == QueryMode.Unified) return false
        val mode = draftQuery.mode as? QueryMode.Source ?: return false
        if (registry.adapterFor(mode.source) !is FacetedSearchSourceAdapter) return false
        return resolveSupportedScope(prefix, supportedSearchScopes) != null
    }

    private fun resolveInputTerm(input: ParsedScopedInput): SearchTerm? {
        val explicitScope = input.explicitScope
        if (explicitScope != null) {
            if (!canUseParsedScope(input)) {
                tagInputValidationMessage = when (draftQuery.mode) {
                    QueryMode.Unified -> UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE
                    is QueryMode.Source -> UNSUPPORTED_SEARCH_SCOPE_MESSAGE
                }
                return null
            }
            val resolvedScope = requireNotNull(
                resolveSupportedScope(explicitScope, supportedSearchScopes),
            )
            selectedSearchScope = resolvedScope
            return SearchTerm(
                value = input.value,
                facet = requireNotNull(resolvedScope.facet),
                sourceNamespace = resolvedScope.sourceNamespace ?: explicitScope.sourceNamespace,
            )
        }

        val selectedScope = selectedSearchScope
            .takeIf { scope -> !scope.isAll && scope in supportedSearchScopes }
        return if (selectedScope == null) {
            SearchTerm(value = input.value)
        } else {
            SearchTerm(
                value = input.value,
                facet = requireNotNull(selectedScope.facet),
                sourceNamespace = selectedScope.sourceNamespace,
            )
        }
    }

    private fun canAddTermToMode(term: SearchTerm): Boolean {
        if (draftQuery.mode != QueryMode.Unified || term.isPortableGeneralTag) return true
        tagInputValidationMessage = UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE
        return false
    }

    private fun prepareModeForPostTerm(post: Post, term: SearchTerm): Boolean {
        if (term.isPortableGeneralTag) return true

        val sourceMode = QueryMode.Source(post.id.source)
        if (!isModeAvailable(sourceMode)) return false
        if (draftQuery.mode == sourceMode) return true

        draftQuery = draftQuery.copy(
            mode = sourceMode,
            includeTerms = draftQuery.includeTerms.filter(SearchTerm::isPortableGeneralTag),
            excludeTerms = draftQuery.excludeTerms.filter(SearchTerm::isPortableGeneralTag),
        )
        resetUnsupportedSearchScope()
        clearTagInputUiState()
        return true
    }

    private fun resetUnsupportedSearchScope() {
        val scopes = supportedSearchScopes
        if (selectedSearchScope in scopes) return
        selectedSearchScope = FacetedSearchScope.All.takeIf { scope -> scope in scopes }
            ?: FacetedSearchScope.All
        clearAutocompleteSuggestions()
    }

    private fun clearTagInputUiState() {
        clearAutocompleteSuggestions()
        tagInputValidationMessage = null
    }

    private fun rankSuggestionsByPrefix(
        suggestions: List<TagSuggestion>,
        prefix: String,
        limit: Int,
    ): List<TagSuggestion> {
        val normalizedPrefix = normalizeMatchToken(prefix)
        if (normalizedPrefix.isBlank() || limit <= 0) return emptyList()
        return suggestions
            .asSequence()
            .filter { suggestion ->
                normalizeMatchToken(suggestion.text).contains(normalizedPrefix)
            }
            .distinctBy { suggestion -> suggestion.text.trim().lowercase() }
            .sortedWith(
                compareByDescending<TagSuggestion> { suggestion ->
                    suggestion.count ?: Int.MIN_VALUE
                }.thenBy { suggestion ->
                    !normalizeMatchToken(suggestion.text).startsWith(normalizedPrefix)
                }.thenBy { suggestion -> suggestion.text.lowercase() }
            )
            .take(limit)
            .toList()
    }

    private fun rankFacetedSuggestionsByPrefix(
        suggestions: List<FacetedTagSuggestion>,
        prefix: String,
        limit: Int,
    ): List<FacetedTagSuggestion> {
        val normalizedPrefix = normalizeMatchToken(prefix)
        if (normalizedPrefix.isBlank() || limit <= 0) return emptyList()
        return suggestions
            .asSequence()
            .filter { suggestion ->
                normalizeMatchToken(suggestion.text).contains(normalizedPrefix)
            }
            .distinctBy { suggestion ->
                FacetedSuggestionIdentity(
                    facet = suggestion.facet,
                    sourceNamespace = suggestion.sourceNamespace,
                    normalizedValue = normalizeMatchToken(suggestion.text),
                )
            }
            .sortedWith(
                compareByDescending<FacetedTagSuggestion> { suggestion ->
                    suggestion.count ?: Int.MIN_VALUE
                }.thenBy { suggestion ->
                    !normalizeMatchToken(suggestion.text).startsWith(normalizedPrefix)
                }.thenBy { suggestion -> suggestion.text.lowercase() }
            )
            .take(limit)
            .toList()
    }

    private fun effectiveWeights(enabledSources: Set<SourceKey>): Map<SourceKey, Double> {
        if (enabledSources.isEmpty()) return emptyMap()
        val raw = enabledSources.associateWith { source ->
            runtimeSettings.runtime.sourceWeights[source] ?: 1.0
        }
        val total = raw.values.sum().takeIf { it > 0.0 } ?: enabledSources.size.toDouble()
        return raw.mapValues { (_, weight) -> weight / total }
    }

    private fun defaultQuery(mode: QueryMode = QueryMode.Unified): Query {
        return Query(
            mode = mode,
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    private fun sourceDisplayOrderIndex(source: SourceKey): Int {
        return SOURCE_DISPLAY_ORDER.indexOf(source).takeIf { it >= 0 } ?: Int.MAX_VALUE
    }
}

enum class DateRangePreset {
    NONE,
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
}

enum class NhentaiLanguageFilter {
    ANY,
    ENGLISH,
    CHINESE,
    JAPANESE,
}

private const val PIXIV_UNKNOWN_RETRY_MESSAGE =
    "Pixiv returned a temporary unknown error. Search was reset. Please retry."
private const val GELBOORU_SUGGESTION_REQUIRED_MESSAGE =
    "For Gelbooru, pick a suggested tag from autocomplete."
private const val UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE =
    "Artists, series, characters, groups, types, and languages require a specific source."
private const val UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE =
    "Source-specific search terms were removed when switching to Unified."
private const val UNSUPPORTED_SEARCH_SCOPE_MESSAGE =
    "That search scope is not supported by this source."
private const val TAG_LOOKUP_LIMIT = 20_000
private const val TAG_FETCH_LIMIT = 25
private const val FACETED_AUTOCOMPLETE_LIMIT = 20
private const val FACETED_AUTOCOMPLETE_CACHE_LIMIT = 120
private const val TRENDING_REFRESH_INTERVAL_MS = 12L * 60L * 60L * 1000L
private const val TRENDING_FETCH_PER_SOURCE_LIMIT = 10
private const val TRENDING_PER_SOURCE_CACHE_LIMIT = 40
private const val SOURCE_TRENDING_LIMIT = 20
private const val UNIFIED_TRENDING_LIMIT = 20
private const val SEEN_TAGS_PER_SOURCE_INGEST_LIMIT = 240
private const val MAX_RESOLVED_POST_OVERRIDES_PER_QUERY = 200
private const val MAX_REMEMBERED_QUERY_OVERRIDES = 8
private const val RATE_LIMIT_BACKOFF_FIRST_MS = 30_000L
private const val RATE_LIMIT_BACKOFF_REPEAT_MS = 2L * 60L * 1000L
private const val RATE_LIMIT_REPEAT_WINDOW_MS = 2L * 60L * 1000L
private const val LAST_ACTIVE_QUERY_KEY = "last_active"
private val SOURCE_DISPLAY_ORDER = listOf(
    SourceKey.GELBOORU,
    SourceKey.PIXIV,
    SourceKey.NHENTAI,
    SourceKey.HITOMI,
    SourceKey.IWARA,
    SourceKey.RULE34XXX,
    SourceKey.RULE34PAHEAL,
    SourceKey.RULE34VIDEO,
    SourceKey.RULE34GEN,
    SourceKey.AIBOORU,
)
private val NHENTAI_LANGUAGE_TAG_BY_FILTER = mapOf(
    NhentaiLanguageFilter.ENGLISH to "english",
    NhentaiLanguageFilter.CHINESE to "chinese",
    NhentaiLanguageFilter.JAPANESE to "japanese",
)
private const val NHENTAI_FULL_COLOR_TAG = "full color"
private const val NHENTAI_LANGUAGE_NAMESPACE = "language"
private const val NHENTAI_TAG_NAMESPACE = "tag"
private val SUGGESTION_CANONICALIZATION_SOURCES = setOf(
    SourceKey.PIXIV,
    SourceKey.GELBOORU,
    SourceKey.NHENTAI,
    SourceKey.HITOMI,
    SourceKey.IWARA,
    SourceKey.RULE34XXX,
    SourceKey.RULE34PAHEAL,
    SourceKey.RULE34VIDEO,
    SourceKey.RULE34GEN,
)
private val WHITESPACE_REGEX = Regex("\\s+")
private val PIXIV_TRAILING_PARENTHESIS_REGEX = Regex("\\s*\\([^)]*\\)\\s*$")

private val SEARCH_SCOPE_ORDER = listOf(
    null,
    SearchFacet.TAG,
    SearchFacet.ARTIST,
    SearchFacet.CHARACTER,
    SearchFacet.SERIES,
    SearchFacet.GROUP,
    SearchFacet.TYPE,
    SearchFacet.LANGUAGE,
)
private val SEARCH_SCOPE_COMPARATOR =
    compareBy<FacetedSearchScope> { scope -> SEARCH_SCOPE_ORDER.indexOf(scope.facet) }
        .thenBy { scope -> scope.scopeNamespaceOrder() }
        .thenBy { scope -> scope.sourceNamespace.orEmpty() }

private val SEARCH_SCOPE_PREFIXES = mapOf(
    "tag" to SearchScopePrefix(SearchFacet.TAG, sourceNamespace = "tag"),
    "female" to SearchScopePrefix(
        facet = SearchFacet.TAG,
        sourceNamespace = "female",
        requiresExactNamespace = true,
    ),
    "male" to SearchScopePrefix(
        facet = SearchFacet.TAG,
        sourceNamespace = "male",
        requiresExactNamespace = true,
    ),
    "artist" to SearchScopePrefix(SearchFacet.ARTIST),
    "character" to SearchScopePrefix(SearchFacet.CHARACTER),
    "series" to SearchScopePrefix(SearchFacet.SERIES),
    "parody" to SearchScopePrefix(
        facet = SearchFacet.SERIES,
        sourceNamespace = "parody",
        requiresExactNamespace = true,
    ),
    "group" to SearchScopePrefix(SearchFacet.GROUP),
    "type" to SearchScopePrefix(SearchFacet.TYPE),
    "category" to SearchScopePrefix(
        facet = SearchFacet.TYPE,
        sourceNamespace = "category",
        requiresExactNamespace = true,
    ),
    "language" to SearchScopePrefix(SearchFacet.LANGUAGE),
)

private fun parseScopedInput(input: String): ParsedScopedInput {
    val trimmed = input.trim()
    val isExclude = trimmed.startsWith("-")
    val unsigned = trimmed.removePrefix("-").trim()
    val separatorIndex = unsigned.indexOf(':')
    if (separatorIndex <= 0) {
        return ParsedScopedInput(
            value = unsigned,
            isExclude = isExclude,
            explicitScope = null,
        )
    }

    val rawPrefix = unsigned.substring(0, separatorIndex).trim().lowercase()
    val scope = SEARCH_SCOPE_PREFIXES[rawPrefix]
        ?: return ParsedScopedInput(
            value = unsigned,
            isExclude = isExclude,
            explicitScope = null,
        )
    return ParsedScopedInput(
        value = unsigned.substring(separatorIndex + 1).trim(),
        isExclude = isExclude,
        explicitScope = scope,
    )
}

private fun resolveSupportedScope(
    prefix: SearchScopePrefix,
    supportedScopes: List<FacetedSearchScope>,
): FacetedSearchScope? {
    val exactNamespace = prefix.sourceNamespace?.let { namespace ->
        supportedScopes.firstOrNull { scope ->
            scope.facet == prefix.facet && scope.sourceNamespace == namespace
        }
    }
    if (exactNamespace != null) return exactNamespace
    if (prefix.requiresExactNamespace) return null
    return supportedScopes.firstOrNull { scope ->
        scope.facet == prefix.facet && scope.sourceNamespace == null
    } ?: supportedScopes.firstOrNull { scope -> scope.facet == prefix.facet }
}

private fun FacetedSearchScope.scopeNamespaceOrder(): Int {
    if (sourceNamespace == null) return 0
    return if (facet == SearchFacet.TAG && sourceNamespace == "tag") 0 else 1
}

private fun SearchTerm.normalizedOrNull(): SearchTerm? {
    val normalizedValue = value.trim().takeIf(String::isNotBlank) ?: return null
    val normalizedNamespace = sourceNamespace?.trim()?.takeIf(String::isNotBlank)
    return copy(value = normalizedValue, sourceNamespace = normalizedNamespace)
}

private fun Query.hasSourceOwnedTerms(): Boolean {
    return (includeTerms + excludeTerms).any { term -> !term.isPortableGeneralTag }
}

private fun Query.forMode(mode: QueryMode): ModeQuerySanitization {
    if (mode != QueryMode.Unified) {
        return ModeQuerySanitization(query = copy(mode = mode), removedSourceOwnedTerms = false)
    }
    val portableIncludes = includeTerms.filter(SearchTerm::isPortableGeneralTag)
    val portableExcludes = excludeTerms.filter(SearchTerm::isPortableGeneralTag)
    return ModeQuerySanitization(
        query = copy(
            mode = QueryMode.Unified,
            includeTerms = portableIncludes,
            excludeTerms = portableExcludes,
        ),
        removedSourceOwnedTerms =
            portableIncludes.size != includeTerms.size || portableExcludes.size != excludeTerms.size,
    )
}

private fun FacetedTagSuggestion.toLegacySuggestion(): TagSuggestion {
    return TagSuggestion(
        text = text,
        type = sourceNamespace ?: facet.name.lowercase(),
        count = count,
    )
}

private fun FacetedTagSuggestion.isPortableTagSuggestion(): Boolean {
    return facet == SearchFacet.TAG && sourceNamespace in setOf(null, "tag")
}

private fun FacetedTagSuggestion.toPortableLegacySuggestion(): TagSuggestion {
    return TagSuggestion(
        text = text,
        type = "tag",
        count = count,
    )
}

private fun TagSuggestion.isPortableTagSuggestion(): Boolean {
    return when (type?.trim()?.lowercase()) {
        "artist", "character", "series", "parody", "group", "type", "category", "language",
        "female", "male" -> false
        else -> true
    }
}

private fun SearchTerm.nhentaiLanguageFilterOrNull(): NhentaiLanguageFilter? {
    val hasLanguageMeaning = when {
        facet == SearchFacet.LANGUAGE ->
            sourceNamespace == null || sourceNamespace == NHENTAI_LANGUAGE_NAMESPACE
        isPortableGeneralTag -> true
        else -> false
    }
    if (!hasLanguageMeaning) return null
    return when (normalizeNhentaiTagFilter(value)) {
        "english" -> NhentaiLanguageFilter.ENGLISH
        "chinese" -> NhentaiLanguageFilter.CHINESE
        "japanese" -> NhentaiLanguageFilter.JAPANESE
        else -> null
    }
}

private fun SearchTerm.isNhentaiFullColorFilter(): Boolean {
    val hasGeneralTagMeaning = facet == SearchFacet.TAG &&
        (sourceNamespace == null || sourceNamespace == NHENTAI_TAG_NAMESPACE)
    return hasGeneralTagMeaning && normalizeNhentaiTagFilter(value) == NHENTAI_FULL_COLOR_TAG
}

private fun normalizeNhentaiTagFilter(value: String): String {
    return value
        .trim()
        .lowercase()
        .replace('_', ' ')
        .replace(WHITESPACE_REGEX, " ")
}

private fun Query.directNhentaiGalleryIdCandidate(): String? {
    val supportsDirectLookup = mode == QueryMode.Unified || mode == QueryMode.Source(SourceKey.NHENTAI)
    if (!supportsDirectLookup) return null
    if (excludeTerms.isNotEmpty()) return null

    val searchable = includeTerms.filterNot { term ->
        term.nhentaiLanguageFilterOrNull() != null || term.isNhentaiFullColorFilter()
    }
    if (searchable.size != 1) return null

    val candidate = searchable.single()
    if (!candidate.isPortableGeneralTag) return null
    return candidate.value.trim().takeIf(String::isDigitsOnly)
}

private fun String.isDigitsOnly(): Boolean {
    return isNotBlank() && all { ch -> ch.isDigit() }
}

private data class ResolveFailureRecord(
    val lastFailureAtMs: Long,
    val backoffUntilMs: Long,
    val reason: SourceFailureReason,
)

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

private data class FacetedSuggestionIdentity(
    val facet: SearchFacet,
    val sourceNamespace: String?,
    val normalizedValue: String,
)

private data class ModeQuerySanitization(
    val query: Query,
    val removedSourceOwnedTerms: Boolean,
)
