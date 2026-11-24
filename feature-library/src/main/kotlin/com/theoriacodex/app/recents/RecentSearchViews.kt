package com.theoriacodex.app.recents

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.relativeTimeLabel
import com.theoriacodex.app.source.SourceIcon
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey

internal fun recentSearchQueryText(query: Query, quiet: Color, excluded: Color): AnnotatedString = buildAnnotatedString {
    fun separate() {
        if (length > 0) withStyle(SpanStyle(color = quiet)) { append(", ") }
    }
    query.effectiveIncludeTermGroups.forEach { group ->
        separate()
        group.terms.forEachIndexed { index, term ->
            if (index > 0) withStyle(SpanStyle(color = quiet)) { append(" or ") }
            append(term.value)
        }
    }
    query.excludeTags.forEach { tag ->
        separate()
        withStyle(SpanStyle(color = excluded)) { append("−$tag") }
    }
    if (length == 0) withStyle(SpanStyle(color = quiet)) { append("No tags") }
}

@Composable
internal fun RecentSearchRow(
    entry: RecentSearchEntry,
    now: Long,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    saving: RecentSearchSaving?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .testTag("Recent search:${entry.queryHash}")
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = SEARCH_MENU_LABEL,
                onLongClick = onLongPress,
            )
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecentSearchMark(entry, 36.dp)
        val renaming = saving.renaming(entry)
        if (saving != null && renaming != null) {
            SavedSearchNameField(renaming, saving, MaterialTheme.typography.bodyLarge, Modifier.weight(1f))
            return@Row
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                recentSearchQueryText(entry.query, colors.onSurfaceVariant, colors.error),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    recentSearchContext(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(relativeTimeLabel(now, entry.searchedAtEpochMs), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        saving?.let { SaveSearchControl(entry, it) }
    }
}

@Composable
internal fun RecentSearchTile(
    entry: RecentSearchEntry,
    now: Long,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    saving: RecentSearchSaving?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier
            .testTag("Recent search:${entry.queryHash}")
            .widthIn(max = RECENT_SEARCH_TILE_MAX_WIDTH)
            .clip(CardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = SEARCH_MENU_LABEL,
                onLongClick = onLongPress,
            ),
        shape = CardShape,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = if (saving == null) 12.dp else 0.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecentSearchMark(entry, 32.dp)
            val renaming = saving.renaming(entry)
            if (saving != null && renaming != null) {
                SavedSearchNameField(renaming, saving, MaterialTheme.typography.bodyMedium, Modifier.weight(1f).padding(end = 8.dp))
                return@Row
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    recentSearchQueryText(entry.query, colors.onSurfaceVariant, colors.error),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    relativeTimeLabel(now, entry.searchedAtEpochMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            saving?.let { SaveSearchControl(entry, it) }
        }
    }
}

@Composable
private fun RecentSearchMark(entry: RecentSearchEntry, size: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * MARK_CORNER_FRACTION)).background(colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val source = recentSearchSource(entry)
        if (source != null) {
            SourceIcon(source)
        } else {
            Icon(
                Icons.Default.Search,
                if (entry.kind == RecentSearchKind.MULTI_SEARCH) "Multi-Search" else "Unified search",
                Modifier.size(size * MARK_GLYPH_FRACTION),
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

private fun recentSearchSource(entry: RecentSearchEntry): SourceKey? = (entry.query.mode as? QueryMode.Source)?.source

private fun recentSearchContext(entry: RecentSearchEntry): String =
    recentSearchPresentation(entry).subtitle ?: recentSearchSource(entry)?.displayName().orEmpty()

internal val RECENT_SEARCH_TILE_MAX_WIDTH = 240.dp
private const val SEARCH_MENU_LABEL = "Search actions"
private val CardShape = RoundedCornerShape(14.dp)
private const val MARK_CORNER_FRACTION = 0.28f
private const val MARK_GLYPH_FRACTION = 0.55f
