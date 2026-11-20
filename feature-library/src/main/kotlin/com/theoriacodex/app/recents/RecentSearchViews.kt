package com.theoriacodex.app.recents

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

/**
 * A search's query as text that reads like the composer: required groups in order with quiet "or"
 * alternatives, then exclusions in [excluded] color with a minus sign.
 */
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

/** One entry of the Searches section: up to two lines of query, its context, and a visible save. */
@Composable
internal fun RecentSearchRow(
    entry: RecentSearchEntry,
    now: Long,
    onClick: () -> Unit,
    onPin: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .testTag("Recent search:${entry.queryHash}")
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClickLabel = "Save search", onLongClick = onPin)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecentSearchMark(entry, 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                recentSearchQueryText(entry.query, colors.onSurfaceVariant, colors.error),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    recentSearchContext(entry, colors.primary),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(relativeTimeLabel(now, entry.searchedAtEpochMs), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        onPin?.let { pin ->
            IconButton(onClick = pin) { Icon(Icons.Default.BookmarkBorder, "Save search", tint = colors.primary) }
        }
    }
}

/** A compact search in the All view's per-day row; long-press saves it, as rows do. */
@Composable
internal fun RecentSearchTile(
    entry: RecentSearchEntry,
    now: Long,
    onClick: () -> Unit,
    onPin: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .testTag("Recent search:${entry.queryHash}")
            .widthIn(max = 240.dp)
            .clip(CardShape)
            .combinedClickable(onClick = onClick, onLongClickLabel = "Save search", onLongClick = onPin),
        shape = CardShape,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecentSearchMark(entry, 32.dp)
            Column {
                Text(
                    recentSearchQueryText(entry.query, colors.onSurfaceVariant, colors.error),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val time = relativeTimeLabel(now, entry.searchedAtEpochMs)
                Text(
                    if (entry.kind == RecentSearchKind.FYP) "For You · $time" else time,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The search's source in a tonal tile, or a search glyph when it spans sources; a badge marks For You. */
@Composable
private fun RecentSearchMark(entry: RecentSearchEntry, size: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(size * MARK_CORNER_FRACTION)).background(colors.surfaceVariant),
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
        if (entry.kind == RecentSearchKind.FYP) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(15.dp).clip(CircleShape).background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, Modifier.size(9.dp), tint = colors.onPrimary)
            }
        }
    }
}

/** The single source a search ran on, if it ran on exactly one. */
private fun recentSearchSource(entry: RecentSearchEntry): SourceKey? = when (entry.kind) {
    RecentSearchKind.SOURCE -> (entry.query.mode as? QueryMode.Source)?.source
    RecentSearchKind.FYP -> entry.sources.singleOrNull()
    else -> null
}

/** For You first (in [accent]), then which sources the search covered. */
private fun recentSearchContext(entry: RecentSearchEntry, accent: Color): AnnotatedString = buildAnnotatedString {
    if (entry.kind == RecentSearchKind.FYP) withStyle(SpanStyle(color = accent)) { append("For You") }
    val sources = recentSearchPresentation(entry).subtitle ?: recentSearchSource(entry)?.displayName()
    if (sources != null) {
        if (length > 0) append(" · ")
        append(sources)
    }
}

private val CardShape = RoundedCornerShape(14.dp)
private const val MARK_CORNER_FRACTION = 0.28f
private const val MARK_GLYPH_FRACTION = 0.55f
