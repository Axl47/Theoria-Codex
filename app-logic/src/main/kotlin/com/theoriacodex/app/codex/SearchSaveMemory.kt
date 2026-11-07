package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.query.QueryHash

/**
 * Which Codices received saves from each search during this app session. Saving one post from a
 * search to a Codex makes it likely the next ones go there too, so the save picker ranks those
 * Codices above any tag-based relevance. In memory only: a new session starts fresh.
 */
class SearchSaveMemory {
    private val savesByQuery = mutableMapOf<String, MutableMap<String, Int>>()

    @Synchronized
    fun record(query: Query?, codexId: String) {
        val key = query?.let(QueryHash::from) ?: return
        val counts = savesByQuery.getOrPut(key) { mutableMapOf() }
        counts[codexId] = (counts[codexId] ?: 0) + 1
    }

    /**
     * [relevance] with this search's save targets lifted above every tag score (which stay in 0..1),
     * the most used first.
     */
    @Synchronized
    fun boost(query: Query?, relevance: Map<String, Double>): Map<String, Double> {
        val counts = query?.let { savesByQuery[QueryHash.from(it)] } ?: return relevance
        return relevance + counts.mapValues { (codexId, saves) ->
            SEARCH_SAVE_BOOST + saves.coerceAtMost(MAX_COUNTED_SAVES) * PER_SAVE_BOOST + (relevance[codexId] ?: 0.0) * PER_SAVE_BOOST
        }
    }
}

private const val SEARCH_SAVE_BOOST = 1.0
private const val PER_SAVE_BOOST = 0.01
private const val MAX_COUNTED_SAVES = 50
