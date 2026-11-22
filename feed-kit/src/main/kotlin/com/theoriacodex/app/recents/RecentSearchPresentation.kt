package com.theoriacodex.app.recents

import androidx.compose.foundation.layout.size
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind

data class RecentSearchPresentation(
    val title: String,
    val subtitle: String?,
)

fun recentSearchPresentation(entry: RecentSearchEntry): RecentSearchPresentation {
    val includes = entry.query.effectiveIncludeTermGroups.joinToString(" AND ") { group ->
        if (group.terms.size == 1) group.terms.single().value
        else group.terms.joinToString(prefix = "(", postfix = ")", separator = " OR ") { it.value }
    }
    val exclusions = entry.query.excludeTags.joinToString(" AND ") { tag -> "-$tag" }
    val title = listOf(includes, exclusions).filter(String::isNotBlank).joinToString(" AND ")
        .ifBlank { "No tags" }
    val sourceList = entry.sources.joinToString(", ") { source -> source.displayName() }
        .takeIf(String::isNotBlank)
    val subtitle = when (entry.kind) {
        RecentSearchKind.SOURCE -> null
        RecentSearchKind.UNIFIED, RecentSearchKind.FYP -> sourceList
        RecentSearchKind.MULTI_SEARCH -> sourceList?.let { "Multi-Search · $it" } ?: "Multi-Search"
    }
    return RecentSearchPresentation(title, subtitle)
}
