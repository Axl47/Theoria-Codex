package com.theoriacodex.app.search

import com.theoriacodex.data.repository.SearchedTagKey
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
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

/**
 * Local-first tag suggestion, provider refresh, and trending orchestration for Search. A cached answer and a fetched
 * one resolve the request the same way and assemble from the same steps; a fetch only adds what providers return.
 */
class SearchSuggestionCoordinator internal constructor(
    private val registry: SourceAdapterRegistry,
    private val tagSuggestionStore: TagSuggestionStore,
    private val clock: () -> Long,
    private val supportedSearchScopes: (QueryMode) -> List<FacetedSearchScope>,
    /** Current per-tag search counts from Statistics, read on every keystroke. */
    private val searchCounts: () -> Map<SearchedTagKey, Long> = { emptyMap() },
) {
    private val lastTrendingRefreshAtBySource = java.util.concurrent.ConcurrentHashMap<SourceKey, Long>()
    private val autocompleteRefreshLock = Any()
    private val lastAutocompleteRefreshAtByKey =
        LinkedHashMap<AutocompleteFetchKey, Long>(32, 0.75f, true)

    suspend fun fetchAutocomplete(
        query: Query,
        sources: Set<SourceKey>,
        selectedScope: FacetedSearchScope,
        input: String,
        onUpdate: suspend (SearchAutocompleteResult) -> Unit = {},
    ): SearchAutocompleteResult = when (val request = resolve(query, selectedScope, input)) {
        is AutocompleteRequest.Answered -> request.result
        is AutocompleteRequest.Featured -> featured(input, request, fetchFeatured(request))
        is AutocompleteRequest.Typed -> {
            val searched = searchedTags(request, sources)
            when (request) {
                is AutocompleteRequest.UnifiedTags -> fetchUnified(input, request, sources) { update ->
                    onUpdate(update.copy(searchedTags = searched))
                }
                is AutocompleteRequest.SourceFacets -> facetMatches(
                    input,
                    request,
                    request.facetScope?.let { scope ->
                        fetchFaceted(request.source, request.adapter, request.prefix, scope)
                    }.orEmpty(),
                )
                is AutocompleteRequest.SourceTags -> tagMatches(
                    input,
                    request,
                    fetchTags(request.source, request.adapter, autocompletePrefixForSource(request.source, request.prefix), 20),
                )
            }.copy(searchedTags = searched)
        }
    }

    /** Returns only local knowledge so the route can publish before its network debounce. */
    fun cachedAutocomplete(
        query: Query,
        sources: Set<SourceKey>,
        selectedScope: FacetedSearchScope,
        input: String,
    ): SearchAutocompleteResult = when (val request = resolve(query, selectedScope, input)) {
        is AutocompleteRequest.Answered -> request.result
        is AutocompleteRequest.Featured -> featured(input, request, fetched = emptyList())
        is AutocompleteRequest.Typed -> when (request) {
            is AutocompleteRequest.UnifiedTags -> unifiedMatches(input, request, sources, fetched = emptyMap())
            is AutocompleteRequest.SourceFacets -> facetMatches(input, request, fetched = emptyList())
            is AutocompleteRequest.SourceTags -> tagMatches(input, request, fetched = emptyList())
        }.copy(searchedTags = searchedTags(request, sources))
    }

    /** History for the input's resolved scope; Unified runs only portable general tags, so it offers only those. */
    private fun searchedTags(request: AutocompleteRequest.Typed, sources: Set<SourceKey>): List<SearchTerm> =
        when (request) {
            is AutocompleteRequest.UnifiedTags -> rankSearchedTags(searchCounts(), sources, request.prefix,
                SEARCHED_TAG_SUGGESTION_LIMIT, SearchTerm::isPortableGeneralTag)
            is AutocompleteRequest.SourceRequest -> rankSearchedTags(searchCounts(), setOf(request.source), request.prefix,
                SEARCHED_TAG_SUGGESTION_LIMIT, request.scope::accepts)
        }

    /** Resolves the input's scope against what the draft's source supports, and what kind of answer it needs. */
    private fun resolve(query: Query, selectedScope: FacetedSearchScope, input: String): AutocompleteRequest {
        val parsed = parseScopedInput(input)
        val supported = supportedSearchScopes(query.mode)
        var scope = selectedScope.takeIf { it in supported } ?: FacetedSearchScope.All
        parsed.explicitScope?.let { explicit ->
            if (query.mode == QueryMode.Unified) {
                return AutocompleteRequest.Answered(SearchAutocompleteResult(input, scope, UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE))
            }
            scope = resolveSupportedScope(explicit, supported)
                ?: return AutocompleteRequest.Answered(SearchAutocompleteResult(input, scope, UNSUPPORTED_SEARCH_SCOPE_MESSAGE))
        }
        val prefix = parsed.value
        val source = (query.mode as? QueryMode.Source)?.source
        if (prefix.isBlank()) {
            val adapter = source?.let { registry.adapterFor(it) as? FacetedSearchSourceAdapter }
            val featuredScope = scope.takeIf { !it.isAll && it in supported }
            return if (source == null || adapter == null || featuredScope == null) {
                AutocompleteRequest.Answered(SearchAutocompleteResult(input, scope))
            } else {
                AutocompleteRequest.Featured(source, adapter, featuredScope)
            }
        }
        if (source == null) return AutocompleteRequest.UnifiedTags(scope, prefix)
        return when (val adapter = registry.adapterFor(source)) {
            is FacetedSearchSourceAdapter -> AutocompleteRequest.SourceFacets(
                scope, prefix, source, adapter,
                facetScope = scope.takeIf { it in supported } ?: FacetedSearchScope.All.takeIf { it in supported },
            )
            else -> AutocompleteRequest.SourceTags(scope, prefix, source, adapter)
        }
    }

    private fun featured(
        input: String,
        request: AutocompleteRequest.Featured,
        fetched: List<FacetedTagSuggestion>,
    ) = SearchAutocompleteResult(
        input,
        request.scope,
        facetedAutocomplete = rankFacetedTagSuggestions(
            fetched + tagSuggestionStore.getFaceted(request.source, Int.MAX_VALUE, request.scope),
            prefix = "",
            limit = FACETED_AUTOCOMPLETE_LIMIT,
        ),
    )

    private fun unifiedMatches(
        input: String,
        request: AutocompleteRequest.UnifiedTags,
        sources: Set<SourceKey>,
        fetched: Map<SourceKey, List<TagSuggestion>>,
    ) = SearchAutocompleteResult(
        input = input,
        selectedScope = request.scope,
        autocomplete = rankUnifiedTagSuggestions(
            sources.map { source ->
                source to (fetched[source].orEmpty() + cachedUnifiedSuggestionsForSource(source, request.prefix))
            },
            request.prefix,
            AUTOCOMPLETE_RESULT_LIMIT,
        ),
    )

    private fun facetMatches(
        input: String,
        request: AutocompleteRequest.SourceFacets,
        fetched: List<FacetedTagSuggestion>,
    ): SearchAutocompleteResult {
        val scope = request.facetScope
            ?: return SearchAutocompleteResult(input, request.scope, UNSUPPORTED_SEARCH_SCOPE_MESSAGE)
        val cached = tagSuggestionStore.findFaceted(request.source, request.prefix, FACETED_AUTOCOMPLETE_CACHE_LIMIT, scope)
        return SearchAutocompleteResult(
            input,
            scope,
            facetedAutocomplete = rankFacetedTagSuggestions(fetched + cached, request.prefix, FACETED_AUTOCOMPLETE_LIMIT),
        )
    }

    private fun tagMatches(
        input: String,
        request: AutocompleteRequest.SourceTags,
        fetched: List<TagSuggestion>,
    ): SearchAutocompleteResult {
        val cached = tagSuggestionStore.find(request.source, request.prefix, AUTOCOMPLETE_CACHE_MATCH_LIMIT)
        val candidates = fetched + cached + trendingMatches(request.source, request.prefix)
        return SearchAutocompleteResult(
            input,
            request.scope,
            autocomplete = rankTagSuggestions(candidates, request.prefix, AUTOCOMPLETE_RESULT_LIMIT),
        )
    }

    /** Ranks the cached matches again as each source answers, publishing every update, and returns the last. */
    private suspend fun fetchUnified(
        input: String,
        request: AutocompleteRequest.UnifiedTags,
        sources: Set<SourceKey>,
        onUpdate: suspend (SearchAutocompleteResult) -> Unit,
    ): SearchAutocompleteResult = coroutineScope {
        val updates = Mutex()
        val fetched = mutableMapOf<SourceKey, List<TagSuggestion>>()
        var result = unifiedMatches(input, request, sources, fetched = emptyMap())
        sources.map { source -> async {
            val suggestions = fetchUnifiedSuggestionsForSource(source, request.prefix)
            updates.withLock {
                fetched[source] = suggestions
                result = unifiedMatches(input, request, sources, fetched)
                onUpdate(result)
            }
        } }.awaitAll()
        result
    }

    private fun cachedUnifiedSuggestionsForSource(source: SourceKey, prefix: String): List<TagSuggestion> {
        val adapter = registry.adapterFor(source)
        if (adapter !is FacetedSearchSourceAdapter) {
            return tagSuggestionStore.find(source, prefix, AUTOCOMPLETE_CACHE_MATCH_LIMIT)
                .filter(TagSuggestion::isPortableTagSuggestion)
        }
        val all = adapter.generalTagScope() ?: return emptyList()
        return tagSuggestionStore.findFaceted(source, prefix, AUTOCOMPLETE_CACHE_MATCH_LIMIT, all)
            .filter(FacetedTagSuggestion::isPortableTagSuggestion)
            .map(FacetedTagSuggestion::toPortableLegacySuggestion)
    }

    private suspend fun fetchUnifiedSuggestionsForSource(source: SourceKey, prefix: String): List<TagSuggestion> {
        val adapter = registry.adapterFor(source)
        val sourcePrefix = autocompletePrefixForSource(source, prefix)
        if (adapter !is FacetedSearchSourceAdapter) {
            return fetchTags(source, adapter, sourcePrefix, 10).filter(TagSuggestion::isPortableTagSuggestion)
        }
        val all = adapter.generalTagScope() ?: return emptyList()
        return fetchFaceted(source, adapter, sourcePrefix, all)
            .filter(FacetedTagSuggestion::isPortableTagSuggestion)
            .map(FacetedTagSuggestion::toPortableLegacySuggestion)
    }

    /** Where Unified reads a faceted source's general tags: everything, or its plain tag scope. */
    private fun FacetedSearchSourceAdapter.generalTagScope(): FacetedSearchScope? =
        FacetedSearchScope.All.takeIf { it in this.supportedSearchScopes }
            ?: this.supportedSearchScopes.firstOrNull { it.facet == SearchFacet.TAG && it.sourceNamespace in setOf(null, "tag") }

    /** A source's tags for [prefix] from its provider, kept in the tag store; empty while a recent answer is fresh. */
    private suspend fun fetchTags(source: SourceKey, adapter: SourceAdapter?, prefix: String, limit: Int): List<TagSuggestion> {
        val fetched = fetchProviderAutocomplete(AutocompleteFetchKey(source, normalizeMatchToken(prefix), FacetedSearchScope.All)) {
            adapter?.autocompleteTags(prefix, limit).orEmpty()
        }
        if (fetched.isNotEmpty()) tagSuggestionStore.put(source, fetched, TagSuggestionOrigin.AUTOCOMPLETE)
        return fetched
    }

    /** A faceted source's suggestions in [scope] from its provider, kept in the tag store; empty while fresh. */
    private suspend fun fetchFaceted(
        source: SourceKey,
        adapter: FacetedSearchSourceAdapter,
        prefix: String,
        scope: FacetedSearchScope,
    ): List<FacetedTagSuggestion> {
        val fetched = fetchProviderAutocomplete(AutocompleteFetchKey(source, normalizeMatchToken(prefix), scope)) {
            adapter.autocompleteFaceted(prefix, scope, FACETED_AUTOCOMPLETE_LIMIT)
        }
        if (fetched.isNotEmpty()) tagSuggestionStore.putFaceted(source, fetched, TagSuggestionOrigin.AUTOCOMPLETE)
        return fetched
    }

    private suspend fun fetchFeatured(request: AutocompleteRequest.Featured): List<FacetedTagSuggestion> {
        val featured = runCatchingPreservingCancellation {
            request.adapter.featuredFacetedSuggestions(request.scope, FACETED_AUTOCOMPLETE_LIMIT)
        }.getOrElse {
            emptyList()
        }
        if (featured.isNotEmpty()) tagSuggestionStore.putFaceted(request.source, featured, TagSuggestionOrigin.FEATURED)
        return featured
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

    /** Refreshes stale sources' trending tags in the tag store, then answers like [cachedTrending]. */
    suspend fun fetchTrending(
        query: Query,
        sources: Set<SourceKey>,
        forceRefresh: Boolean = false,
    ): List<TagSuggestion> = coroutineScope {
        val now = clock()
        when (val mode = query.mode) {
            QueryMode.Unified -> {
                if (sources.isEmpty()) return@coroutineScope emptyList()
                sources.filter { source ->
                    shouldRefreshTrending(source, now, forceRefresh, rankedTrending(source, TRENDING_PER_SOURCE_CACHE_LIMIT))
                }.map { source ->
                    async { fetchTrendingForSource(source, TRENDING_FETCH_PER_SOURCE_LIMIT) }
                }.awaitAll()
            }
            is QueryMode.Source -> {
                if (shouldRefreshTrending(mode.source, now, forceRefresh, rankedTrending(mode.source, SOURCE_TRENDING_LIMIT))) {
                    fetchTrendingForSource(mode.source, SOURCE_TRENDING_LIMIT)
                }
            }
        }
        cachedTrending(query, sources)
    }

    fun cachedTrending(
        query: Query,
        sources: Set<SourceKey>,
    ): List<TagSuggestion> = when (val mode = query.mode) {
        QueryMode.Unified -> interleaveTagSuggestions(
            sources.map { source -> source to rankedTrending(source, TRENDING_PER_SOURCE_CACHE_LIMIT) },
            UNIFIED_TRENDING_LIMIT,
        )
        is QueryMode.Source -> rankedTrending(mode.source, SOURCE_TRENDING_LIMIT)
    }

    /** The source's current trending tags that match [prefix], offered alongside its autocomplete. */
    private fun trendingMatches(source: SourceKey, prefix: String): List<TagSuggestion> =
        rankedTrending(source, SOURCE_TRENDING_LIMIT).filter { suggestion -> tagSuggestionMatches(suggestion, prefix) }

    private fun rankedTrending(source: SourceKey, limit: Int): List<TagSuggestion> =
        rankTagSuggestions(tagSuggestionStore.getTrending(source, Int.MAX_VALUE), prefix = "", limit = limit)

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
}

/** What an autocomplete request needs, resolved once for both its cached and its fetched answer. */
private sealed interface AutocompleteRequest {
    /** Needs no suggestions: an input error, or a blank input with nothing featured. */
    data class Answered(val result: SearchAutocompleteResult) : AutocompleteRequest

    /** A blank input in a faceted source's picked scope: that scope's featured tags. */
    data class Featured(
        val source: SourceKey,
        val adapter: FacetedSearchSourceAdapter,
        val scope: FacetedSearchScope,
    ) : AutocompleteRequest

    /** Typed input; [scope] is the input's resolved scope and [prefix] the text to match. */
    sealed interface Typed : AutocompleteRequest {
        val scope: FacetedSearchScope
        val prefix: String
    }

    data class UnifiedTags(override val scope: FacetedSearchScope, override val prefix: String) : Typed

    /** Typed input for the draft's one source. */
    sealed interface SourceRequest : Typed {
        val source: SourceKey
    }

    /** [facetScope] is where the source's suggestions come from, or null when it offers neither [scope] nor All. */
    data class SourceFacets(
        override val scope: FacetedSearchScope,
        override val prefix: String,
        override val source: SourceKey,
        val adapter: FacetedSearchSourceAdapter,
        val facetScope: FacetedSearchScope?,
    ) : SourceRequest

    data class SourceTags(
        override val scope: FacetedSearchScope,
        override val prefix: String,
        override val source: SourceKey,
        val adapter: SourceAdapter?,
    ) : SourceRequest
}
