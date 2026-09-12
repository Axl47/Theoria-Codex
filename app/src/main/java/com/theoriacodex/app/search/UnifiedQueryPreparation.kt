package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.tags.normalizeGelbooruToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Reuses successful compatibility lookups; preparation belongs to the Gelbooru request only. */
internal class UnifiedQueryPreparation(private val clock: () -> Long) {
    private val mutex = Mutex()
    private val cache = linkedMapOf<String, Mapping>()

    suspend fun prepareGelbooru(adapter: SourceAdapter, query: Query): Query {
        val terms = (query.effectiveIncludeTermGroups.flatMap { it.terms } + query.excludeTerms)
            .map { it.value.trim() }.filter(String::isNotBlank).distinct()
        val mappings = terms.mapConcurrent { value -> value to resolve(adapter, value) }.toMap()
        return query.withIncludeTermGroups(query.effectiveIncludeTermGroups.map { group ->
            SearchTermGroup(group.terms.map { term -> term.copy(value = mappings[term.value.trim()] ?: term.value) })
        }).copy(excludeTerms = query.excludeTerms.map { term ->
            term.copy(value = mappings[term.value.trim()] ?: term.value)
        })
    }

    private suspend fun resolve(adapter: SourceAdapter, value: String): String {
        val key = normalizeGelbooruToken(value)
        mutex.withLock { cache[key]?.takeIf { clock() - it.atMs < CACHE_TTL_MS } }?.let { return it.value }
        val result = runCatchingPreservingCancellation {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { adapter.autocompleteTags(key, 1) }
        }.getOrNull() ?: return value
        val mapped = result.firstOrNull()?.text?.trim()?.takeIf(String::isNotBlank) ?: value
        mutex.withLock {
            cache.remove(key)
            cache[key] = Mapping(mapped, clock())
            while (cache.size > MAX_MAPPINGS) cache.remove(cache.keys.first())
        }
        return mapped
    }

    private data class Mapping(val value: String, val atMs: Long)
    private companion object {
        const val CACHE_TTL_MS = 30 * 60_000L
        const val LOOKUP_TIMEOUT_MS = 2_000L
        const val MAX_MAPPINGS = 512
    }
}
