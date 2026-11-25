package com.theoriacodex.app.recents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.sortModeLabel
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.model.SortMode

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
        RecentSearchKind.UNIFIED -> sourceList
        RecentSearchKind.MULTI_SEARCH -> sourceList?.let { "Multi-Search · $it" } ?: "Multi-Search"
    }
    return RecentSearchPresentation(title, subtitle)
}

val RecentSearchEntry.sortLabel: String?
    get() = query.sort.takeUnless { it == SortMode.NEWEST }?.let(::sortModeLabel)

@Composable
fun SavedSearchName(entry: SavedSearchEntry, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(entry.name, Modifier.weight(1f, fill = false), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        entry.search.sortLabel?.let { Text(it, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
}
