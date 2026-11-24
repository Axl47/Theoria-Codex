package com.theoriacodex.app.series

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.data.repository.ChapterDownload
import com.theoriacodex.app.ui.formatTimelineTime
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.series.SeriesKind
import java.text.DateFormat
import java.util.Date

/** The entry count ("12 episodes", or "3 of 12 episodes" while narrowed) and the sort and filter button. */
@Composable
internal fun ChapterListHeader(
    words: EntryWords,
    totalCount: Int,
    visibleCount: Int,
    filterActive: Boolean,
    onOpenOptions: () -> Unit,
) {
    val label = if (visibleCount == totalCount) words.count(totalCount) else "$visibleCount of ${words.count(totalCount)}"
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onOpenOptions) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = "Sort and filter ${words.many}",
                tint = if (filterActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun EmptyFilteredChapters(words: EntryWords, onClear: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("No matching ${words.many}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClear) { Text("Clear filters") }
    }
}

/**
 * An entry card: title, upload date / scanlator / progress line, optional download mark, and long-press quick actions,
 * worded for the series' [kind].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EntryRow(
    entry: StoredSeriesEntry,
    kind: SeriesKind,
    enabled: Boolean,
    hasPreviousUnread: Boolean,
    download: ChapterDownload?,
    canToggleDownload: Boolean,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    onMarkPreviousRead: () -> Unit,
    onToggleDownload: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val detail = remember(entry, kind) { entryDetail(entry, kind, dates) }
    val rowAlpha = when {
        !enabled -> DISABLED_ALPHA
        entry.read -> READ_ALPHA
        else -> 1f
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
        Surface(
            shape = CHAPTER_CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().alpha(rowAlpha),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .entryRowInteraction(entry.entry.address, "${kind.words.one.capitalized()} actions", enabled, onClick) { menuOpen = true }
                    .padding(start = 16.dp, end = if (canToggleDownload) 6.dp else 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = entry.entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (canToggleDownload) {
                    Spacer(Modifier.width(4.dp))
                    DownloadMark(download, onToggleDownload)
                }
            }
        }
        // Composed only while open: a long chapter list would otherwise hold a closed menu per row.
        if (menuOpen) EntryContextMenu(
            words = kind.words,
            read = entry.read,
            hasPreviousUnread = hasPreviousUnread,
            download = download,
            canToggleDownload = canToggleDownload,
            onDismiss = { menuOpen = false },
            onToggleRead = onToggleRead,
            onMarkPreviousRead = onMarkPreviousRead,
            onToggleDownload = onToggleDownload,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.entryRowInteraction(key: String, actionsLabel: String, enabled: Boolean, onClick: () -> Unit, onLongPress: () -> Unit): Modifier =
    if (enabled) {
        combinedClickable(onClick = onClick, onLongClick = onLongPress, onLongClickLabel = actionsLabel)
    } else {
        pointerInput(key) { detectTapGestures(onLongPress = { onLongPress() }) }
            .semantics {
                disabled()
                onLongClick(label = actionsLabel) { onLongPress(); true }
            }
    }

@Composable
private fun EntryContextMenu(
    words: EntryWords,
    read: Boolean,
    hasPreviousUnread: Boolean,
    download: ChapterDownload?,
    canToggleDownload: Boolean,
    onDismiss: () -> Unit,
    onToggleRead: () -> Unit,
    onMarkPreviousRead: () -> Unit,
    onToggleDownload: () -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Mark as ${if (read) words.notDone else words.done}") },
            onClick = { onDismiss(); onToggleRead() },
        )
        if (hasPreviousUnread) {
            DropdownMenuItem(
                text = { Text("Mark previous as ${words.done}") },
                onClick = { onDismiss(); onMarkPreviousRead() },
            )
        }
        if (canToggleDownload) {
            val downloadLabel = when (download) {
                null -> "Download"
                is ChapterDownload.Queued -> "Cancel download"
                ChapterDownload.OnDevice -> "Delete download"
            }
            DropdownMenuItem(
                text = { Text(downloadLabel) },
                onClick = { onDismiss(); onToggleDownload() },
            )
        }
    }
}

/** A chapter row's download state, which a tap moves on: download, stop the download, or delete it from the device. */
@Composable
private fun DownloadMark(download: ChapterDownload?, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        when (download) {
            null -> Icon(
                Icons.Outlined.ArrowCircleDown,
                contentDescription = "Download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is ChapterDownload.Queued -> Box(contentAlignment = Alignment.Center) {
                val ring = Modifier.size(DOWNLOAD_RING)
                val progress = download.progress
                if (progress == null) CircularProgressIndicator(ring, strokeWidth = 2.dp)
                else CircularProgressIndicator({ progress }, ring, strokeWidth = 2.dp)
                Icon(Icons.Default.Close, contentDescription = "Cancel download", Modifier.size(DOWNLOAD_STOP))
            }
            ChapterDownload.OnDevice -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Delete download",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Date, translator group, and progress joined on one subtitle line. Progress is how far the user is in an entry left
 * unfinished ("Page 7 of 18", or "8:12 / 23:40" as the Viewer's timeline writes it), or what a finished one is ("Read",
 * "Watched").
 */
internal fun entryDetail(
    entry: StoredSeriesEntry,
    kind: SeriesKind,
    dates: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM),
): String? {
    val date = entry.entry.uploadedAtEpochMs?.let { dates.format(Date(it)) }
    val group = entry.entry.translatorGroup?.trim()?.takeIf(String::isNotEmpty)
    val status = when {
        entry.read -> kind.words.finished
        entry.position > 0 -> when (kind) {
            SeriesKind.MANGA -> entry.length?.let { "Page ${entry.position} of $it" } ?: "Page ${entry.position}"
            SeriesKind.ANIME -> listOfNotNull(entry.position, entry.length).joinToString(" / ", transform = ::formatTimelineTime)
        }
        else -> null
    }
    return listOfNotNull(date, group, status).joinToString(" · ").takeIf(String::isNotEmpty)
}

private val CHAPTER_CARD_SHAPE = RoundedCornerShape(12.dp)
private val DOWNLOAD_RING = 22.dp
private val DOWNLOAD_STOP = 12.dp
private const val READ_ALPHA = 0.5f
private const val DISABLED_ALPHA = 0.38f
