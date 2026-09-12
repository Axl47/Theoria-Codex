package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeGelbooruToken
import com.theoriacodex.domain.tags.normalizeMatchToken
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Local-first tag suggestion, provider refresh, and trending orchestration for Search. */
internal class SearchSuggestionCoordinator(
    private val registry: SourceAdapterRegistry,
    private val tagSuggestionStore: TagSuggestionStore,
    private val clock: () -> Long,
    private val supportedSearchScopes: (QueryMode) -> List<FacetedSearchScope>,
    private val effectiveEnabledSources: (SearchSourceScope) -> Set<SourceKey>,
) {
    private val lastTrendingRefreshAtBySource = java.util.concurrent.ConcurrentHashMap<SourceKey, Long>()
    private val autocompleteRefreshLock = Any()
    private val lastAutocompleteRefreshAtByKey =
        LinkedHashMap<AutocompleteFetchKey, Long>(32, 0.75f, true)

    internal suspend fun fetchAutocomplete(
        query: Query,
        sourceScope: SearchSourceScope,
        selectedScope: FacetedSearchScope,
        input: String,
        trending: List<TagSuggestion>,
        onUpdate: suspend (SearchAutocompleteResult) -> Unit = {},
    ): SearchAutocompleteResult {
        val request = resolveAutocompleteRequest(query, selectedScope, input)
        request.validationMessage?.let { message ->
            return SearchAutocompleteResult(input, request.scope, message)
        }
        if (request.prefix.isBlank()) return fetchFeaturedAutocomplete(query, request.scope, input)
        return when (val mode = query.mode) {
            QueryMode.Unified -> fetchUnifiedAutocomplete(sourceScope, request.scope, input, request.prefix, onUpdate)
            is QueryMode.Source -> fetchSourceAutocomplete(mode, request.scope, input, request.prefix, trending)
        }
    }

    /** Returns only local knowledge so the route can publish before its network debounce. */
    internal fun cachedAutocomplete(
        query: Query,
        sourceScope: SearchSourceScope,
        selectedScope: FacetedSearchScope,
        input: String,
        trending: List<TagSuggestion>,
    ): SearchAutocompleteResult {
        val request = resolveAutocompleteRequest(query, selectedScope, input)
        request.validationMessage?.let { message ->
            return SearchAutocompleteResult(input, request.scope, message)
        }
        if (request.prefix.isBlank()) {
            return cachedFeaturedAutocomplete(query, request.scope, input)
        }
        return when (val mode = query.mode) {
            QueryMode.Unified -> cachedUnifiedAutocomplete(sourceScope, request.scope, input, request.prefix)
            is QueryMode.Source -> cachedSourceAutocomplete(mode, request.scope, input, request.prefix, trending)
        }
    }

    private fun resolveAutocompleteRequest(
        query: Query,
        selectedScope: FacetedSearchScope,
        input: String,
    ): ResolvedAutocompleteRequest {
        val parsed = parseScopedInput(input)
        val supported = supportedSearchScopes(query.mode)
        var scope = selectedScope.takeIf { it in supported } ?: FacetedSearchScope.All
        parsed.explicitScope?.let { explicit ->
            if (query.mode == QueryMode.Unified) {
                return ResolvedAutocompleteRequest(
                    scope = scope,
                    prefix = parsed.value,
                    validationMessage = UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE,
                )
            }
            scope = resolveSupportedScope(explicit, supported)
                ?: return ResolvedAutocompleteRequest(
                    scope = scope,
                    prefix = parsed.value,
                    validationMessage = UNSUPPORTED_SEARCH_SCOPE_MESSAGE,
                )
        }
        return ResolvedAutocompleteRequest(scope = scope, prefix = parsed.value)
    }

    private suspend fun fetchUnifiedAutocomplete(
        sourceScope: SearchSourceScope,
        selectedScope: FacetedSearchScope,
        input: String,
        prefix: String,
        onUpdate: suspend (SearchAutocompleteResult) -> Unit,
    ): SearchAutocompleteResult = coroutineScope {
        val enabled = effectiveEnabledSources(sourceScope)
        val updates = Mutex()
        val fetched = mutableMapOf<SourceKey, List<TagSuggestion>>()
        var result = cachedUnifiedAutocomplete(sourceScope, selectedScope, input, prefix)
        enabled.map { source -> async {
            val suggestions = fetchUnifiedSuggestionsForSource(source, prefix)
            updates.withLock {
                fetched[source] = suggestions
                result = SearchAutocompleteResult(
                    input = input,
                    selectedScope = selectedScope,
                    autocomplete = rankUnifiedTagSuggestions(
                        enabled.map { key -> key to (fetched[key].orEmpty() + cachedUnifiedSuggestionsForSource(key, prefix)) },
                        prefix, AUTOCOMPLETE_RESULT_LIMIT,
                    ),
                )
                onUpdate(result)
            }
        } }.awaitAll()
        result
    }

    private fun cachedUnifiedAutocomplete(
        sourceScope: SearchSourceScope,
        selectedScope: FacetedSearchScope,
        input: String,
        prefix: String,
    ): SearchAutocompleteResult {
        val candidates = effectiveEnabledSources(sourceScope).map { source ->
            source to cachedUnifiedSuggestionsForSource(source, prefix)
        }
        return SearchAutocompleteResult(
            input = input,
            selectedScope = selectedScope,
            autocomplete = rankUnifiedTagSuggestions(candidates, prefix, AUTOCOMPLETE_RESULT_LIMIT),
        )
    }

    private fun cachedUnifiedSuggestionsForSource(
        source: SourceKey,
        prefix: String,
    ): List<TagSuggestion> {
        val adapter = registry.adapterFor(source)
        if (adapter !is FacetedSearchSourceAdapter) {
            return tagSuggestionStore.find(source, prefix, AUTOCOMPLETE_CACHE_MATCH_LIMIT)
                .filter(TagSuggestion::isPortableTagSuggestion)
        }
        val all = FacetedSearchScope.All.takeIf { it in adapter.supportedSearchScopes }
            ?: adapter.supportedSearchScopes.firstOrNull {
                it.facet == SearchFacet.TAG && it.sourceNamespace in setOf(null, "tag")
            }
            ?: return emptyList()
        return tagSuggestionStore.findFaceted(source, prefix, AUTOCOMPLETE_CACHE_MATCH_LIMIT, all)
            .filter(FacetedTagSuggestion::isPortableTagSuggestion)
            .map(FacetedTagSuggestion::toPortableLegacySuggestion)
    }

    private suspend fun fetchUnifiedSuggestionsForSource(
        source: SourceKey,
        prefix: String,
    ): List<TagSuggestion> {
        val adapter = registry.adapterFor(source)
        val sourcePrefix = autocompletePrefixForSource(source, prefix)
        if (adapter !is FacetedSearchSourceAdapter) {
            val suggestions = fetchProviderAutocomplete(
                key = AutocompleteFetchKey(source, normalizeMatchToken(sourcePrefix), FacetedSearchScope.All),
            ) {
                adapter?.autocompleteTags(sourcePrefix, 10).orEmpty()
            }
            if (suggestions.isNotEmpty()) {
                tagSuggestionStore.put(source, suggestions, TagSuggestionOrigin.AUTOCOMPLETE)
            }
            return suggestions.filter(TagSuggestion::isPortableTagSuggestion)
        }
        val all = FacetedSearchScope.All.takeIf { it in adapter.supportedSearchScopes }
            ?: adapter.supportedSearchScopes.firstOrNull {
                it.facet == SearchFacet.TAG && it.sourceNamespace in setOf(null, "tag")
            }
            ?: return emptyList()
        val suggestions = fetchProviderAutocomplete(
            key = AutocompleteFetchKey(source, normalizeMatchToken(sourcePrefix), all),
        ) {
            adapter.autocompleteFaceted(sourcePrefix, all, FACETED_AUTOCOMPLETE_LIMIT)
        }
        if (suggestions.isNotEmpty()) {
            tagSuggestionStore.putFaceted(source, suggestions, TagSuggestionOrigin.AUTOCOMPLETE)
        }
        return suggestions.filter(FacetedTagSuggestion::isPortableTagSuggestion)
            .map(FacetedTagSuggestion::toPortableLegacySuggestion)
    }

    private suspend fun fetchSourceAutocomplete(
        mode: QueryMode.Source,
        selectedScope: FacetedSearchScope,
        input: String,
        prefix: String,
        trending: List<TagSuggestion>,
    ): SearchAutocompleteResult {
        val adapter = registry.adapterFor(mode.source)
        if (adapter is FacetedSearchSourceAdapter) {
            val supported = supportedSearchScopes(mode)
            val scope = selectedScope.takeIf { it in supported }
                ?: FacetedSearchScope.All.takeIf { it in supported }
                ?: return SearchAutocompleteResult(input, selectedScope, UNSUPPORTED_SEARCH_SCOPE_MESSAGE)
            val fetched = fetchProviderAutocomplete(
                key = AutocompleteFetchKey(mode.source, normalizeMatchToken(prefix), scope),
            ) {
                adapter.autocompleteFaceted(prefix, scope, FACETED_AUTOCOMPLETE_LIMIT)
            }
            if (fetched.isNotEmpty()) {
                tagSuggestionStore.putFaceted(mode.source, fetched, TagSuggestionOrigin.AUTOCOMPLETE)
            }
            val candidates = fetched + tagSuggestionStore.findFaceted(
                mode.source,
                prefix,
                FACETED_AUTOCOMPLETE_CACHE_LIMIT,
                scope,
            )
            val ranked = rankFacetedTagSuggestions(candidates, prefix, FACETED_AUTOCOMPLETE_LIMIT)
            return SearchAutocompleteResult(
                input,
                scope,
                autocomplete = ranked.map(FacetedTagSuggestion::toLegacySuggestion),
                facetedAutocomplete = ranked,
            )
        }
        val sourcePrefix = autocompletePrefixForSource(mode.source, prefix)
        val fetched = fetchProviderAutocomplete(
            key = AutocompleteFetchKey(
                mode.source,
                normalizeMatchToken(sourcePrefix),
                FacetedSearchScope.All,
            ),
        ) {
            adapter?.autocompleteTags(sourcePrefix, 20).orEmpty()
        }
        if (fetched.isNotEmpty()) {
            tagSuggestionStore.put(mode.source, fetched, TagSuggestionOrigin.AUTOCOMPLETE)
        }
        val cached = tagSuggestionStore.find(mode.source, prefix, AUTOCOMPLETE_CACHE_MATCH_LIMIT)
        val candidates = fetched + cached + trending.filter { suggestion ->
            tagSuggestionMatches(suggestion, prefix)
        }
        return SearchAutocompleteResult(
            input,
            selectedScope,
            autocomplete = rankTagSuggestions(candidates, prefix, AUTOCOMPLETE_RESULT_LIMIT),
        )
    }

    private fun cachedSourceAutocomplete(
        mode: QueryMode.Source,
        selectedScope: FacetedSearchScope,
        input: String,
        prefix: String,
        trending: List<TagSuggestion>,
    ): SearchAutocompleteResult {
        val adapter = registry.adapterFor(mode.source)
        if (adapter is FacetedSearchSourceAdapter) {
            val supported = supportedSearchScopes(mode)
            val scope = selectedScope.takeIf { it in supported }
                ?: FacetedSearchScope.All.takeIf { it in supported }
                ?: return SearchAutocompleteResult(input, selectedScope, UNSUPPORTED_SEARCH_SCOPE_MESSAGE)
            val ranked = rankFacetedTagSuggestions(
                tagSuggestionStore.findFaceted(
                    mode.source,
                    prefix,
                    FACETED_AUTOCOMPLETE_CACHE_LIMIT,
                    scope,
                ),
                prefix,
                FACETED_AUTOCOMPLETE_LIMIT,
            )
            return SearchAutocompleteResult(
                input,
                scope,
                autocomplete = ranked.map(FacetedTagSuggestion::toLegacySuggestion),
                facetedAutocomplete = ranked,
            )
        }
        val candidates = tagSuggestionStore.find(
            mode.source,
            prefix,
            AUTOCOMPLETE_CACHE_MATCH_LIMIT,
        ) + trending.filter { suggestion -> tagSuggestionMatches(suggestion, prefix) }
        return SearchAutocompleteResult(
            input,
            selectedScope,
            autocomplete = rankTagSuggestions(candidates, prefix, AUTOCOMPLETE_RESULT_LIMIT),
        )
    }

    private suspend fun fetchFeaturedAutocomplete(
        query: Query,
        selectedScope: FacetedSearchScope,
        input: String,
    ): SearchAutocompleteResult {
        val mode = query.mode as? QueryMode.Source
        val adapter = mode?.let { registry.adapterFor(it.source) as? FacetedSearchSourceAdapter }
        val scope = selectedScope.takeIf { !it.isAll && it in supportedSearchScopes(query.mode) }
        if (mode == null || adapter == null || scope == null) {
            return SearchAutocompleteResult(input, selectedScope)
        }
        val featured = try {
            adapter.featuredFacetedSuggestions(scope, FACETED_AUTOCOMPLETE_LIMIT)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
        if (featured.isNotEmpty()) {
            tagSuggestionStore.putFaceted(mode.source, featured, TagSuggestionOrigin.FEATURED)
        }
        val suggestions = (featured + tagSuggestionStore.getFaceted(
            mode.source,
            FACETED_AUTOCOMPLETE_LIMIT,
            scope,
        )).distinctBy { suggestion ->
            Triple(suggestion.facet, suggestion.sourceNamespace, normalizeMatchToken(suggestion.text))
        }.take(FACETED_AUTOCOMPLETE_LIMIT)
        return SearchAutocompleteResult(
            input,
            scope,
            autocomplete = suggestions.map(FacetedTagSuggestion::toLegacySuggestion),
            facetedAutocomplete = suggestions,
        )
    }

    private fun cachedFeaturedAutocomplete(
        query: Query,
        selectedScope: FacetedSearchScope,
        input: String,
    ): SearchAutocompleteResult {
        val mode = query.mode as? QueryMode.Source
        val adapter = mode?.let { registry.adapterFor(it.source) as? FacetedSearchSourceAdapter }
        val scope = selectedScope.takeIf { !it.isAll && it in supportedSearchScopes(query.mode) }
        if (mode == null || adapter == null || scope == null) {
            return SearchAutocompleteResult(input, selectedScope)
        }
        val suggestions = tagSuggestionStore.getFaceted(
            mode.source,
            FACETED_AUTOCOMPLETE_LIMIT,
            scope,
        )
        return SearchAutocompleteResult(
            input,
            scope,
            autocomplete = suggestions.map(FacetedTagSuggestion::toLegacySuggestion),
            facetedAutocomplete = suggestions,
        )
    }

    private suspend fun <T> fetchProviderAutocomplete(
        key: AutocompleteFetchKey,
        block: suspend () -> List<T>,
    ): List<T> {
        if (autocompleteIsFresh(key)) return emptyList()
        return try {
            val fetched = withTimeout(AUTOCOMPLETE_REQUEST_TIMEOUT_MS) { block() }
            recordAutocompleteRefresh(key)
            fetched
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            emptyList()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun autocompleteIsFresh(key: AutocompleteFetchKey): Boolean =
        synchronized(autocompleteRefreshLock) {
            lastAutocompleteRefreshAtByKey[key]
                ?.let { refreshedAt -> clock() - refreshedAt < AUTOCOMPLETE_REFRESH_INTERVAL_MS }
                ?: false
        }

    private fun recordAutocompleteRefresh(key: AutocompleteFetchKey) {
        synchronized(autocompleteRefreshLock) {
            lastAutocompleteRefreshAtByKey[key] = clock()
            while (lastAutocompleteRefreshAtByKey.size > MAX_AUTOCOMPLETE_REFRESH_KEYS) {
                lastAutocompleteRefreshAtByKey.entries.iterator().run {
                    next()
                    remove()
                }
            }
        }
    }

    internal suspend fun fetchTrending(
        query: Query,
        sourceScope: SearchSourceScope,
        forceRefresh: Boolean = false,
    ): List<TagSuggestion> = coroutineScope {
        val now = clock()
        when (val mode = query.mode) {
            QueryMode.Unified -> {
                val enabled = effectiveEnabledSources(sourceScope)
                if (enabled.isEmpty()) return@coroutineScope emptyList()
                val cached = enabled.associateWith {
                    tagSuggestionStore.getTrending(it, TRENDING_PER_SOURCE_CACHE_LIMIT)
                }
                enabled.filter { source ->
                    shouldRefreshTrending(source, now, forceRefresh, cached[source].orEmpty())
                }.map { source ->
                    async { fetchTrendingForSource(source, TRENDING_FETCH_PER_SOURCE_LIMIT) }
                }.awaitAll()
                interleaveTagSuggestions(
                    enabled.map { source ->
                        source to tagSuggestionStore.getTrending(source, TRENDING_PER_SOURCE_CACHE_LIMIT)
                    },
                    UNIFIED_TRENDING_LIMIT,
                )
            }
            is QueryMode.Source -> {
                val cached = tagSuggestionStore.getTrending(mode.source, SOURCE_TRENDING_LIMIT)
                if (shouldRefreshTrending(mode.source, now, forceRefresh, cached)) {
                    fetchTrendingForSource(mode.source, SOURCE_TRENDING_LIMIT)
                }
                tagSuggestionStore.getTrending(mode.source, SOURCE_TRENDING_LIMIT)
            }
        }
    }

    internal fun cachedTrending(
        query: Query,
        sourceScope: SearchSourceScope,
    ): List<TagSuggestion> = when (val mode = query.mode) {
        QueryMode.Unified -> {
            val enabled = effectiveEnabledSources(sourceScope)
            interleaveTagSuggestions(
                enabled.map { source ->
                    source to tagSuggestionStore.getTrending(source, TRENDING_PER_SOURCE_CACHE_LIMIT)
                },
                UNIFIED_TRENDING_LIMIT,
            )
        }
        is QueryMode.Source -> tagSuggestionStore.getTrending(mode.source, SOURCE_TRENDING_LIMIT)
    }

    private fun shouldRefreshTrending(
        source: SourceKey,
        now: Long,
        force: Boolean,
        cached: List<TagSuggestion>,
    ): Boolean = force || cached.isEmpty() ||
        lastTrendingRefreshAtBySource[source]?.let { now - it >= TRENDING_REFRESH_INTERVAL_MS } != false

    private suspend fun fetchTrendingForSource(source: SourceKey, limit: Int): List<TagSuggestion> {
        val fetched = runCatchingPreservingCancellation {
            registry.adapterFor(source)?.trendingTags(limit).orEmpty()
        }.getOrDefault(emptyList())
        lastTrendingRefreshAtBySource[source] = clock()
        if (fetched.isNotEmpty()) tagSuggestionStore.replaceTrending(source, fetched)
        return fetched
    }

    private fun autocompletePrefixForSource(source: SourceKey, input: String): String {
        val normalized = parseScopedInput(input).value
        return when (source) {
            SourceKey.GELBOORU, SourceKey.IWARA, SourceKey.RULE34XXX ->
                normalizeGelbooruToken(normalized)
            else -> normalized
        }
    }
}
