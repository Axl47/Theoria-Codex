package com.theoriacodex.app.search

import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.nio.charset.StandardCharsets
import java.util.Base64

internal object SearchSavedStateKeys {
    const val DRAFT_QUERY = "search_draft_query_v2"
    const val RESTORATION_STARTED = "search_restoration_started"
    const val RESTORATION_COMPLETED = "search_restoration_completed"
    const val SCROLL_INDEX = "search_scroll_index"
    const val SCROLL_OFFSET = "search_scroll_offset"
}

/** Bundle-safe codec: SavedStateHandle stores one compact String, never a domain object graph. */
internal object SearchSavedQueryCodec {
    private const val NULL = "~"

    fun encode(query: Query): String {
        return listOf(
            encodeMode(query.mode),
            query.sort.name,
            query.dateRange?.fromEpochMs?.toString() ?: NULL,
            query.dateRange?.toEpochMs?.toString() ?: NULL,
            query.minScore?.toString() ?: NULL,
            encodeGroups(query.effectiveIncludeTermGroups),
            encodeTerms(query.excludeTerms),
        ).joinToString("|")
    }

    fun decode(encoded: String): Query? {
        return runCatching {
            val parts = encoded.split('|')
            require(parts.size == 7)
            Query(
                mode = decodeMode(parts[0]),
                includeTerms = decodeGroups(parts[5]).flatMap(SearchTermGroup::terms),
                excludeTerms = decodeTerms(parts[6]),
                sort = SortMode.valueOf(parts[1]),
                dateRange = if (parts[2] == NULL && parts[3] == NULL) {
                    null
                } else {
                    DateRange(
                        fromEpochMs = parts[2].takeUnless { it == NULL }?.toLong(),
                        toEpochMs = parts[3].takeUnless { it == NULL }?.toLong(),
                    )
                },
                minScore = parts[4].takeUnless { it == NULL }?.toInt(),
                includeTermGroups = decodeGroups(parts[5]),
            )
        }.getOrNull()
    }

    private fun encodeMode(mode: QueryMode): String {
        return when (mode) {
            QueryMode.Unified -> "u"
            is QueryMode.Source -> "s:${mode.source.name}"
        }
    }

    private fun decodeMode(encoded: String): QueryMode {
        if (encoded == "u") return QueryMode.Unified
        return QueryMode.Source(SourceKey.valueOf(encoded.removePrefix("s:")))
    }

    private fun encodeTerms(terms: List<SearchTerm>): String {
        return terms.joinToString(".") { term ->
            encodeText(
                listOf(term.facet.name, term.sourceNamespace.orEmpty(), term.value)
                    .joinToString("\n"),
            )
        }
    }

    private fun encodeGroups(groups: List<SearchTermGroup>): String {
        return groups.joinToString("~") { group -> encodeTerms(group.terms) }
    }

    private fun decodeGroups(encoded: String): List<SearchTermGroup> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split('~').map { group -> SearchTermGroup(decodeTerms(group)) }
    }

    private fun decodeTerms(encoded: String): List<SearchTerm> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split('.').map { item ->
            val parts = decodeText(item).split('\n', limit = 3)
            require(parts.size == 3)
            SearchTerm(
                value = parts[2],
                facet = SearchFacet.valueOf(parts[0]),
                sourceNamespace = parts[1].takeIf(String::isNotBlank),
            )
        }
    }

    private fun encodeText(value: String): String {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeText(value: String): String {
        return String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }
}
