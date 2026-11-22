package com.theoriacodex.app.series

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.ErrorState
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesStatus
import java.text.DateFormat
import java.util.Date

/**
 * One series: its cover and names, Follow, a button that continues where the user left off, and every chapter with
 * whether it was read and how far. [onOpenEntry] gets the address of the chapter to read.
 */
@Composable
fun SeriesPageScreen(
    viewModel: SeriesPageViewModel,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onOpenExtensions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // New chapters of followed series are announced, so each follow asks for the permission while it is missing.
    // Refusing changes nothing else: the series is followed and the shelf still counts its new chapters.
    val askToNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val toggleFollowed = {
        viewModel.toggleFollowed {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) askToNotify.launch(permission)
        }
    }
    val missing = state.missing
    Column(modifier.fillMaxSize()) {
        // Without its source the page has no source name to show.
        SecondaryScreenAppBar(title = state.sourceName.ifEmpty { state.stored?.series?.title.orEmpty() }, onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            state.stored?.let { stored ->
                item("header") { SeriesHeader(stored, state, toggleFollowed, onOpenEntry) }
                item("about") { SeriesAbout(stored.series) }
            }
            val failure = state.failure
            when {
                missing != null -> item("missing") { MissingSourceRow(missing, onOpenExtensions) }
                failure != null && state.entries.isEmpty() -> item("failure") { ErrorState(failure, onRetry = viewModel::refresh) }
                failure != null -> item("failure") { FailureRow(viewModel::refresh, Modifier.padding(horizontal = 16.dp), failure) }
                state.refreshing && state.entries.isEmpty() -> item("loading") { LoadingState() }
                state.entries.isEmpty() -> item("empty") { EmptyState("No chapters") }
            }
            items(state.entries, key = { it.entry.address }) { entry ->
                EntryRow(entry, enabled = missing == null) { onOpenEntry(entry.entry.address) }
            }
        }
    }
}

/** Says the series' extension is off or gone, which is why its chapters cannot be opened, and leads to where to fix it. */
@Composable
private fun MissingSourceRow(missing: SeriesSourceMissing, onOpenExtensions: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(missing.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onOpenExtensions) { Text(EXTENSIONS_TITLE) }
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
            statusLine(stored.series.status, state.entries.size)?.let { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilterChip(
                selected = stored.followed,
                onClick = onToggleFollowed,
                label = { Text(if (stored.followed) "Following" else "Follow") },
            )
            state.continueWith?.let { entry ->
                Button(onClick = { onOpenEntry(entry.entry.address) }, enabled = state.missing == null) {
                    Text(continueLabel(state.started, entry), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The synopsis, cut to a few lines that a tap opens in full when it is longer, then the genres on one line. Both come
 * from storage, so they show before the source answers and while its extension is off.
 */
@Composable
private fun SeriesAbout(series: Series) {
    val description = series.description
    if (description == null && series.genres.isEmpty()) return
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (description != null) {
            var expanded by rememberSaveable(series.id) { mutableStateOf(false) }
            var cut by remember(description) { mutableStateOf(false) }
            Text(
                description,
                Modifier.fillMaxWidth().clickable(enabled = cut || expanded) { expanded = !expanded },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { cut = it.hasVisualOverflow },
            )
        }
        if (series.genres.isNotEmpty()) {
            Text(
                series.genres.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val DESCRIPTION_LINES = 4

/** A chapter: its name, then how far the user is in it or, untouched or finished, when it came out. Read ones fade. */
@Composable
private fun EntryRow(entry: StoredSeriesEntry, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
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

/** "Ongoing · 44 chapters": the status, when the source gave one, then the chapter count once chapters are known. */
internal fun statusLine(status: SeriesStatus, chapters: Int): String? =
    listOfNotNull(status.label, chapterCount(chapters).takeIf { chapters > 0 }).joinToString(" · ").ifEmpty { null }

private val SeriesStatus.label: String?
    get() = when (this) {
        SeriesStatus.UNKNOWN -> null
        SeriesStatus.ONGOING -> "Ongoing"
        SeriesStatus.COMPLETED -> "Completed"
        SeriesStatus.LICENSED -> "Licensed"
        SeriesStatus.PUBLISHING_FINISHED -> "Finished"
        SeriesStatus.CANCELLED -> "Cancelled"
        SeriesStatus.ON_HIATUS -> "Hiatus"
    }

/** "Page 7 of 18" for a chapter left unfinished, "Read" for a finished one, else its date when the source gives one. */
internal fun entryDetail(entry: StoredSeriesEntry): String? = when {
    entry.read -> "Read"
    entry.lastPage > 0 -> entry.pageCount?.let { "Page ${entry.lastPage} of $it" } ?: "Page ${entry.lastPage}"
    else -> entry.entry.uploadedAtEpochMs?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
}

private const val READ_ALPHA = 0.5f
