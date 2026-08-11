package com.theoriacodex.domain.query

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import java.util.Locale

object QueryHash {
    private const val VERSION = "v3"
    private val whitespace = Regex("\\s+")

    fun from(query: Query): String {
        val mode = when (val modeValue = query.mode) {
            QueryMode.Unified -> "unified"
            is QueryMode.Source -> "source:${modeValue.source.name}"
        }
        val includeGroups = query.effectiveIncludeTermGroups
            .mapNotNull { group ->
                group.terms.mapNotNull { term -> term.hashEntry(polarity = "include") }
                    .sorted()
                    .takeIf(List<String>::isNotEmpty)
                    ?.joinToString(separator = ",", prefix = "group[", postfix = "]")
            }
            .sorted()
        val exclusions = query.excludeTerms
            .mapNotNull { term -> term.hashEntry(polarity = "exclude") }
            .sorted()
        val terms = (includeGroups + exclusions).joinToString(separator = ";")
        val date = query.dateRange?.let { "${it.fromEpochMs ?: "-"}:${it.toEpochMs ?: "-"}" } ?: "none"
        val score = query.minScore?.toString() ?: "none"
        return listOf(VERSION, mode, terms, query.sort.name, date, score).joinToString("|")
    }

    private fun SearchTerm.hashEntry(polarity: String): String? {
        val normalizedValue = value.normalizedHashValue()
        if (normalizedValue.isEmpty()) return null
        val namespace = sourceNamespace?.normalizedHashValue()
        return buildString {
            append(polarity)
            append(':')
            append(facet.name)
            append(':')
            append(namespace.lengthPrefixedNullable())
            append(':')
            append(normalizedValue.lengthPrefixed())
        }
    }

    private fun String.normalizedHashValue(): String {
        return trim()
            .lowercase(Locale.ROOT)
            .replace(whitespace, " ")
    }

    private fun String.lengthPrefixed(): String = "$length:$this"

    private fun String?.lengthPrefixedNullable(): String {
        return if (this == null) "null" else "value:${lengthPrefixed()}"
    }
}
