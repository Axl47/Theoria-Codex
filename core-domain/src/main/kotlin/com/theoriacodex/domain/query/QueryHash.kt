package com.theoriacodex.domain.query

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode

object QueryHash {
    fun from(query: Query): String {
        val mode = when (val modeValue = query.mode) {
            QueryMode.Unified -> "unified"
            is QueryMode.Source -> "source:${modeValue.source.name}"
        }
        val include = query.includeTags.map(String::trim).filter(String::isNotEmpty).sorted().joinToString(",")
        val exclude = query.excludeTags.map(String::trim).filter(String::isNotEmpty).sorted().joinToString(",")
        val date = query.dateRange?.let { "${it.fromEpochMs ?: "-"}:${it.toEpochMs ?: "-"}" } ?: "none"
        val score = query.minScore?.toString() ?: "none"
        return listOf(mode, include, exclude, query.sort.name, date, score).joinToString("|")
    }
}
