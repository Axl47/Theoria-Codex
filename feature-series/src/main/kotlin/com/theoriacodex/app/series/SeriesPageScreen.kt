package com.theoriacodex.app.series

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.formatTimelineTime
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.data.repository.ChapterDownload
import com.theoriacodex.data.repository.DownloadChoice
import com.theoriacodex.data.repository.SeriesDownloads
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesStatus
import java.text.DateFormat
import java.util.Date

/**
 * One series: its cover and names, Follow, a button that continues where the user left off, and every entry (chapter or
 * episode, by the series' kind) with whether it was finished and how far, and whether it is downloaded. [onOpenEntry]
 * gets the address of the entry to open.
 * The overflow menu moves the series to another source through [switchSource]: [onOpenSeries] shows another series'
 * page, and [onSwitched] shows the series once moved, in place of this page.
 */
@Composable
fun SeriesPageScreen(
    viewModel: SeriesPageViewModel,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onOpenExtensions: () -> Unit,
    switchSource: SwitchSourceViewModel,
    onOpenSeries: (SeriesId) -> Unit,
    onSwitched: (SeriesId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // New entries of followed series are announced, so each follow asks for the permission while it is missing.
    // Refusing changes nothing else: the series is followed and the shelf still counts its new entries.
    val askToNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val toggleFollowed = {
        viewModel.toggleFollowed {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) askToNotify.launch(permission)
        }
    }
    val missing = state.sourceMissing
    Column(modifier.fillMaxSize()) {
        // Without its source the page has no source name to show.
        SecondaryScreenAppBar(title = state.sourceName, onBack = onBack, actions = {
            state.stored?.let { stored -> SeriesPageMenu(switchSource) { switchSource.open(stored.series.title) } }
        })
        SwitchSourceSheet(switchSource, onOpenSeries, onSwitched)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            state.stored?.let { stored ->
                item("header") { SeriesHeader(stored, state, toggleFollowed, onOpenEntry) }
                item("about") { SeriesAbout(stored.series) }
            }
            val failure = state.failure
            when {
                missing -> item("missing") { MissingSourceRow(onOpenExtensions) }
                failure != null -> item("failure") {
                    SourceLoadFailure(
                        failure.message, state.source?.key, state.sourceName, viewModel::refresh.takeIf { failure.retryable },
                        Modifier.padding(horizontal = 16.dp), compact = state.entries.isNotEmpty(),
                    )
                }
                state.refreshing && state.entries.isEmpty() -> item("loading") { LoadingState() }
                state.entries.isEmpty() -> item("empty") { EmptyState(state.kind.words.none) }
            }
            entryList(state, viewModel, onOpenEntry)
        }
    }
}

/** The entry count with the Download menu, then every entry, each with its download mark where the page offers downloads. */
private fun LazyListScope.entryList(state: SeriesPageState, viewModel: SeriesPageViewModel, onOpenEntry: (String) -> Unit) {
    val downloads = viewModel.canDownload
    if (state.entries.isNotEmpty()) {
        item("entries") {
            EntryListHeader(state, downloads, viewModel::download, viewModel::deleteDownloads, viewModel::setDownloadNewChapters)
        }
    }
    items(state.entries, key = { it.entry.address }) { entry ->
        val address = entry.entry.address
        val download = state.downloads[address]
        EntryRow(
            entry,
            state.kind,
            enabled = state.canOpen(address),
            onClick = { onOpenEntry(address) },
            // Without its source a chapter cannot download; one queued or on the device can still be cancelled or deleted.
            trailing = if (downloads && (download != null || !state.sourceMissing)) {
                { DownloadMark(download) { viewModel.toggleDownload(address) } }
            } else null,
        )
    }
}

/** The page's overflow menu, shown while another source is on to switch to. */
@Composable
private fun SeriesPageMenu(switchSource: SwitchSourceViewModel, onSwitchSource: () -> Unit) {
    val offered = switchSource.state.collectAsStateWithLifecycle().value.others.isNotEmpty()
    if (!offered) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(SWITCH_SOURCE) }, onClick = {
                open = false
                onSwitchSource()
            })
        }
    }
}

/** Says the series' extension cannot be used, which is why its entries cannot be opened, and leads to where to fix it. */
@Composable
private fun MissingSourceRow(onOpenExtensions: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Extension unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onOpenExtensions) { Text(EXTENSIONS_TITLE) }
    }
}

@Composable
private fun SeriesHeader(stored: StoredSeries, state: SeriesPageState, onToggleFollowed: () -> Unit, onOpenEntry: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CompositionLocalProvider(LocalSeriesSources provides listOfNotNull(state.source)) { SeriesCover(stored.series, Modifier.width(112.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stored.series.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            stored.series.author?.let { author ->
                Text(author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The entry count heads the entry list.
            stored.series.status.label?.let { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilterChip(
                selected = stored.followed,
                onClick = onToggleFollowed,
                label = { Text(if (stored.followed) "Following" else "Follow") },
            )
            state.continueWith?.let { entry ->
                Button(onClick = { onOpenEntry(entry.entry.address) }, enabled = state.canOpen(entry.entry.address)) {
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

/** An entry: its name, then [entryDetail], and [trailing] at the end. Finished ones fade. */
@Composable
private fun EntryRow(entry: StoredSeriesEntry, kind: SeriesKind, enabled: Boolean, onClick: () -> Unit, trailing: (@Composable () -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (entry.read) READ_ALPHA else 1f)
            .padding(start = 16.dp, end = if (trailing == null) 16.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(entry.entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            entryDetail(entry, kind)?.let { detail ->
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/**
 * The entry count, and the Download menu where the page offers downloads: chapters to queue, deleting the series'
 * downloads, and for a followed series whether new chapters download by themselves.
 */
@Composable
private fun EntryListHeader(
    state: SeriesPageState,
    downloads: Boolean,
    onDownload: (DownloadChoice) -> Unit,
    onDeleteDownloads: () -> Unit,
    onDownloadNewChapters: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            state.kind.words.count(state.entries.size),
            Modifier.weight(1f).padding(vertical = 12.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        if (!downloads) return@Row
        var open by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { open = true }) { Text("Download") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                fun choose(action: () -> Unit) {
                    open = false
                    action()
                }
                if (!state.sourceMissing) {
                    DOWNLOAD_CHOICES.forEach { (choice, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { choose { onDownload(choice) } })
                    }
                }
                DropdownMenuItem(text = { Text("Delete downloads") }, enabled = state.downloads.isNotEmpty(), onClick = { choose(onDeleteDownloads) })
                val stored = state.stored
                if (stored?.followed == true) {
                    HorizontalDivider()
                    DownloadNewChaptersItem(stored.downloadNewChapters) { choose { onDownloadNewChapters(it) } }
                }
            }
        }
    }
}

/** Checked as other menus show their choice, and said as checked or not to accessibility services. */
@Composable
private fun DownloadNewChaptersItem(on: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text("Download new chapters") },
        trailingIcon = { if (on) Icon(Icons.Default.Check, contentDescription = null) },
        onClick = { onChange(!on) },
        modifier = Modifier.semantics { toggleableState = ToggleableState(on) },
    )
}

private val DOWNLOAD_CHOICES = listOf(
    DownloadChoice.NEXT_UNREAD to "Next ${SeriesDownloads.NEXT_UNREAD_COUNT} unread",
    DownloadChoice.ALL_UNREAD to "All unread",
    DownloadChoice.ALL to "All",
)

/** A chapter row's download state, which a tap moves on: download, stop the download, or delete it from the device. */
@Composable
private fun DownloadMark(download: ChapterDownload?, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        when (download) {
            null -> Icon(Icons.Outlined.Download, contentDescription = "Download")
            is ChapterDownload.Queued -> Box(contentAlignment = Alignment.Center) {
                val ring = Modifier.size(DOWNLOAD_RING)
                val progress = download.progress
                if (progress == null) CircularProgressIndicator(ring, strokeWidth = 2.dp) else CircularProgressIndicator({ progress }, ring, strokeWidth = 2.dp)
                Icon(Icons.Default.Close, contentDescription = "Cancel download", Modifier.size(DOWNLOAD_STOP))
            }
            ChapterDownload.OnDevice -> Icon(Icons.Default.DownloadDone, contentDescription = "Delete download")
        }
    }
}

private val DOWNLOAD_RING = 22.dp
private val DOWNLOAD_STOP = 12.dp

internal fun continueLabel(started: Boolean, entry: StoredSeriesEntry): String =
    if (started) "Continue · ${entry.entry.name}" else "Start"

private val SeriesStatus.label: String?
    get() = when (this) {
        SeriesStatus.UNKNOWN -> null
        SeriesStatus.ONGOING -> "Ongoing"
        SeriesStatus.COMPLETED -> "Completed"
        SeriesStatus.LICENSED -> "Licensed"
        SeriesStatus.PUBLISHING_FINISHED -> "Finished"
        SeriesStatus.CANCELLED -> "Cancelled"
        SeriesStatus.ON_HIATUS -> "Hiatus"
        SeriesStatus.UPCOMING -> "Upcoming"
    }

/**
 * How far the user is in an entry left unfinished ("Page 7 of 18", or "8:12 / 23:40" as the Viewer's timeline writes
 * it), what a finished one is ("Read", "Watched"), else when it came out, when the source says.
 */
internal fun entryDetail(entry: StoredSeriesEntry, kind: SeriesKind): String? = when {
    entry.read -> kind.words.finished
    entry.position > 0 -> when (kind) {
        SeriesKind.MANGA -> entry.length?.let { "Page ${entry.position} of $it" } ?: "Page ${entry.position}"
        SeriesKind.ANIME -> listOfNotNull(entry.position, entry.length).joinToString(" / ", transform = ::formatTimelineTime)
    }
    else -> entry.entry.uploadedAtEpochMs?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
}

private const val READ_ALPHA = 0.5f
