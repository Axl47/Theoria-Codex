package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Counts use an explicit provider capability; unavailable decisions prevent repeated sheet requests. */
internal class TagCountCoordinator(
    private val registry: SourceAdapterRegistry,
    private val store: TagSuggestionStore,
    private val prepareTag: (SourceKey, String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timeoutMs: Long = 4_000L,
) {
    private val sourceLocks = SourceKey.entries.associateWith { Mutex() }
    private val missingUntil = LinkedHashMap<Pair<SourceKey, String>, Long>()

    suspend fun fetch(source: SourceKey, tags: List<String>, supplemental: Map<String, Int?>): Map<String, Int?> =
        withContext(dispatcher) {
            val requested = tags.map(String::trim).filter(String::isNotBlank).distinct()
            val resolved = requested.associateWith { store.getCount(source, it) ?: supplemental[it] }.toMutableMap()
            val adapter = registry.adapterFor(source) as? TagCountLookupSourceAdapter ?: return@withContext resolved
            val completed = withTimeoutOrNull(timeoutMs) {
                sourceLocks.getValue(source).withLock {
                    val missing = requested.filter { tag ->
                        resolved[tag] = store.getCount(source, tag) ?: resolved[tag]
                        resolved[tag] == null && synchronized(missingUntil) {
                            (missingUntil[source to sourceTagKey(source, tag)] ?: 0L) <= clock()
                        }
                    }
                    if (missing.isEmpty()) return@withLock
                    val counts = runCatchingPreservingCancellation {
                        adapter.fetchTagCounts(missing.map { prepareTag(source, it) })
                    }.getOrDefault(emptyMap())
                    val indexed = counts.mapKeys { sourceTagKey(source, it.key) }
                    if (counts.isNotEmpty()) store.put(source,
                        counts.map { TagSuggestion(it.key, "tag_count_lookup", it.value) }, TagSuggestionOrigin.COUNT_LOOKUP)
                    missing.forEach { tag ->
                        resolved[tag] = indexed[sourceTagKey(source, tag)]
                        if (resolved[tag] == null) markMissing(source, listOf(tag))
                    }
                }
                true
            }
            if (completed == null) markMissing(source, requested.filter { resolved[it] == null })
            resolved
        }
    private fun markMissing(source: SourceKey, tags: List<String>) = synchronized(missingUntil) {
        tags.forEach { missingUntil[source to sourceTagKey(source, it)] = clock() + 60_000L }
        while (missingUntil.size > 2_000) missingUntil.remove(missingUntil.keys.first())
    }

}
