package com.theoriacodex.app.series

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.theoriacodex.data.repository.DownloadChoice
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesDownloads
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.seriesKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

internal enum class SeriesAction { MARK_ALL_DONE, MARK_ALL_NOT_DONE, REFRESH, SWITCH_SOURCE }

internal data class SeriesMenuItem(val action: SeriesAction, val label: String, val enabled: Boolean)

internal data class SeriesActions(
    val words: EntryWords,
    val anyUnfinished: Boolean,
    val anyProgress: Boolean,
    val canRefresh: Boolean,
    val canSwitchSource: Boolean,
) {
    val items: List<SeriesMenuItem>
        get() = listOfNotNull(
            SeriesMenuItem(SeriesAction.MARK_ALL_DONE, "Mark all as ${words.done}", anyUnfinished),
            SeriesMenuItem(SeriesAction.MARK_ALL_NOT_DONE, "Mark all as ${words.notDone}", anyProgress),
            SeriesMenuItem(SeriesAction.REFRESH, "Refresh", canRefresh),
            SeriesMenuItem(SeriesAction.SWITCH_SOURCE, SWITCH_SOURCE, true).takeIf { canSwitchSource },
        )
}

internal class SeriesActionHandlers(
    private val markAllRead: (Boolean) -> Unit,
    private val refresh: () -> Unit,
    private val switchSource: () -> Unit,
) {
    fun run(action: SeriesAction) = when (action) {
        SeriesAction.MARK_ALL_DONE -> markAllRead(true)
        SeriesAction.MARK_ALL_NOT_DONE -> markAllRead(false)
        SeriesAction.REFRESH -> refresh()
        SeriesAction.SWITCH_SOURCE -> switchSource()
    }
}

internal fun SeriesPageState.actions(canSwitchSource: Boolean) = SeriesActions(
    words = kind.words,
    anyUnfinished = entries.any { !it.read },
    anyProgress = entries.any { it.read || it.position > 0 },
    canRefresh = !sourceMissing && !refreshing,
    canSwitchSource = canSwitchSource,
)

internal fun FollowedSeries.actions(sources: List<SeriesSource>?, checking: Boolean): SeriesActions {
    val id = series.id
    val installed = sources.orEmpty().any { it.key == id.source }
    return SeriesActions(
        words = id.source.seriesKind.words,
        anyUnfinished = unreadCount > 0,
        anyProgress = started,
        canRefresh = installed && !checking,
        canSwitchSource = sources.orEmpty().switchTargets(id).isNotEmpty(),
    )
}

internal fun List<SeriesSource>.switchTargets(from: SeriesId): List<SeriesSource> =
    filter { it.key != from.source && it.kind == from.source.seriesKind }

@Composable
internal fun SeriesActionItems(actions: SeriesActions, onChoose: (SeriesAction) -> Unit) {
    actions.items.forEach { item ->
        DropdownMenuItem(text = { Text(item.label) }, enabled = item.enabled, onClick = { onChoose(item.action) })
    }
}

@Composable
internal fun SeriesHoldMenu(
    expanded: Boolean,
    actions: SeriesActions,
    canDownload: Boolean,
    onChoose: (SeriesAction) -> Unit,
    onDownload: (DownloadChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var downloading by remember(expanded) { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (downloading) {
            DOWNLOAD_CHOICES.forEach { (choice, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    onDismiss()
                    onDownload(choice)
                })
            }
        } else {
            SeriesActionItems(actions) { action ->
                onDismiss()
                onChoose(action)
            }
            if (canDownload) DropdownMenuItem(text = { Text(DOWNLOAD) }, onClick = { downloading = true })
        }
    }
}

internal suspend fun SeriesRepository.markAllRead(id: SeriesId, read: Boolean): BulkReadUndo? {
    val restore = setAllRead(id, read)
    if (restore.isEmpty) return null
    val words = id.source.seriesKind.words
    return BulkReadUndo("Marked all as ${if (read) words.done else words.notDone}", restore)
}

internal fun CoroutineScope.changeDownloads(change: suspend () -> Unit) = launch {
    try {
        change()
    } catch (error: IOException) {
        Diagnostics.report("series", "Downloads could not change", error)
    }
}

internal val DOWNLOAD_CHOICES = listOf(
    DownloadChoice.NEXT_UNREAD to "Next ${SeriesDownloads.NEXT_UNREAD_COUNT} unread",
    DownloadChoice.ALL_UNREAD to "All unread",
    DownloadChoice.ALL to "All",
)

internal const val DOWNLOAD = "Download"
internal const val MORE_OPTIONS = "More options"
