package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.query.QueryHash

class SearchSaveMemory {
    private val savesByQuery = mutableMapOf<String, MutableMap<String, Int>>()

    @Synchronized
    fun record(query: Query?, codexId: String) {
        val key = query?.let(QueryHash::from) ?: return
        val counts = savesByQuery.getOrPut(key) { mutableMapOf() }
        counts[codexId] = (counts[codexId] ?: 0) + 1
    }

    @Synchronized
    fun boost(query: Query?, relevance: Map<String, Double>): Map<String, Double> {
        val counts = query?.let { savesByQuery[QueryHash.from(it)] } ?: return relevance
        return relevance + counts.mapValues { (codexId, saves) ->
            SEARCH_SAVE_TIER + saves.coerceAtMost(MAX_COUNTED_SAVES) * PER_SAVE_BOOST + (relevance[codexId] ?: 0.0) * PER_SAVE_BOOST
        }
    }
}

const val SEARCH_SAVE_TIER = 1.0
private const val PER_SAVE_BOOST = 0.01
private const val MAX_COUNTED_SAVES = 50
