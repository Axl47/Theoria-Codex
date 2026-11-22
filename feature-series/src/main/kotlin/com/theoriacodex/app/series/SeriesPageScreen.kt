package com.theoriacodex.app.series

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.ErrorState
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import java.text.DateFormat
import java.util.Date

/**
 * One series: its cover and names, Follow, a button that continues where the user left off, and every chapter with
 * whether it was read and how far. [onOpenEntry] gets the address of the chapter to read.
 */
@Composable
fun SeriesPageScreen(viewModel: SeriesPageViewModel, onBack: () -> Unit, onOpenEntry: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        SecondaryScreenAppBar(title = state.sourceName, onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            state.stored?.let { stored ->
                item("header") { SeriesHeader(stored, state, viewModel::toggleFollowed, onOpenEntry) }
            }
            val failure = state.failure
            when {
                failure != null && state.entries.isEmpty() -> item("failure") { ErrorState(failure, onRetry = viewModel::refresh) }
                failure != null -> item("failure") { FailureRow(viewModel::refresh, Modifier.padding(horizontal = 16.dp)) }
                state.refreshing && state.entries.isEmpty() -> item("loading") { LoadingState() }
                state.entries.isEmpty() -> item("empty") { EmptyState("No chapters") }
            }
            if (state.entries.isNotEmpty()) {
                item("count") {
                    Text(
                        chapterCount(state.entries.size),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            items(state.entries, key = { it.entry.address }) { entry -> EntryRow(entry) { onOpenEntry(entry.entry.address) } }
        }
    }
}

@Composable
private fun SeriesHeader(stored: StoredSeries, state: SeriesPageState, onToggleFollowed: () -> Unit, onOpenEntry: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SeriesCover(stored.series, Modifier.width(112.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stored.series.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            stored.series.author?.let { author ->
                Text(author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilterChip(
                selected = stored.followed,
                onClick = onToggleFollowed,
                label = { Text(if (stored.followed) "Following" else "Follow") },
            )
            state.continueWith?.let { entry ->
                Button(onClick = { onOpenEntry(entry.entry.address) }) {
                    Text(continueLabel(state.started, entry), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A chapter: its name, then how far the user is in it or, untouched or finished, when it came out. Read ones fade. */
@Composable
private fun EntryRow(entry: StoredSeriesEntry, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (entry.read) READ_ALPHA else 1f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(entry.entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        entryDetail(entry)?.let { detail ->
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun continueLabel(started: Boolean, entry: StoredSeriesEntry): String =
    if (started) "Continue · ${entry.entry.name}" else "Start"

internal fun chapterCount(count: Int): String = if (count == 1) "1 chapter" else "$count chapters"

/** "Page 7 of 18" for a chapter left unfinished, "Read" for a finished one, else its date when the source gives one. */
internal fun entryDetail(entry: StoredSeriesEntry): String? = when {
    entry.read -> "Read"
    entry.lastPage > 0 -> entry.pageCount?.let { "Page ${entry.lastPage} of $it" } ?: "Page ${entry.lastPage}"
    else -> entry.entry.uploadedAtEpochMs?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
}

private const val READ_ALPHA = 0.5f
