package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagsMatch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Typed counts stay separate from the plain-tag store so equal names cannot cross namespaces. */
internal class SearchTermCountCoordinator(
    private val registry: SourceAdapterRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timeoutMs: Long = 4_000L,
) {
    private data class Decision(val count: Int?, val expiresAt: Long)
    private val decisions = LinkedHashMap<Pair<SourceKey, SearchTerm>, Decision>()
    private val locks = SourceKey.entries.associateWith { Mutex() }

    suspend fun fetch(source: SourceKey, terms: List<SearchTerm>): Map<SearchTerm, Int?> = withContext(dispatcher) {
        val requested = terms.distinct()
        val adapter = registry.adapterFor(source) as? FacetedSearchSourceAdapter
            ?: return@withContext requested.associateWith { null }
        withTimeoutOrNull(timeoutMs) {
            locks.getValue(source).withLock {
                requested.mapConcurrent(concurrency = 2) { term ->
                    if (cached(source, term) == null) lookup(source, term, adapter)
                }
            }
        }
        requested.associateWith { term ->
            val decision = cached(source, term)
            if (decision == null) remember(source, term, null)
            decision?.count
        }
    }

    private suspend fun lookup(source: SourceKey, term: SearchTerm, adapter: FacetedSearchSourceAdapter) {
        val scope = FacetedSearchScope(term.facet, term.sourceNamespace)
        val count = if (term.value.isBlank() || scope !in adapter.supportedSearchScopes) null else {
            runCatchingPreservingCancellation {
                adapter.autocompleteFaceted(term.value, scope, 30).firstOrNull { suggestion ->
                    suggestion.facet == term.facet && suggestion.sourceNamespace == term.sourceNamespace &&
                        sourceTagsMatch(source, suggestion.text, term.value)
                }?.count?.takeIf { it >= 0 }
            }.getOrNull()
        }
        remember(source, term, count)
    }

    private fun cached(source: SourceKey, term: SearchTerm): Decision? = synchronized(decisions) {
        decisions[source to term]?.takeIf { it.expiresAt > clock() }
    }

    private fun remember(source: SourceKey, term: SearchTerm, count: Int?) = synchronized(decisions) {
        decisions[source to term] = Decision(count, clock() + if (count == null) 60_000L else 300_000L)
        while (decisions.size > 2_000) decisions.remove(decisions.keys.first())
    }
}
