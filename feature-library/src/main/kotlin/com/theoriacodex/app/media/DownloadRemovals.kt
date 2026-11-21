package com.theoriacodex.app.media

import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoOffer
import com.theoriacodex.app.ui.undo.UndoSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Removes downloads as deferred destruction: they hide at once with Undo at the anchor, and their records
 * (and files, for Delete) go only when the offer expires, through [commit]. Undo just shows them again,
 * so a process death inside the window deletes nothing. Offers run in [scope], which outlives the sheet.
 */
class DownloadRemovals(
    private val commit: (ids: Set<Long>, deleteFiles: Boolean) -> Unit,
    private val scope: CoroutineScope,
) {
    private val hiddenIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Downloads hidden while their removal can still be undone, or while it commits. */
    val hidden: StateFlow<Set<Long>> = hiddenIds.asStateFlow()

    fun remove(undo: UndoSlot, ids: Set<Long>, deleteFiles: Boolean, anchor: UndoAnchor) {
        if (ids.isEmpty()) return
        hiddenIds.update { it + ids }
        undo.offer(scope, UndoOffer(removalLabel(ids.size, deleteFiles), anchor, onExpire = {
            commit(ids, deleteFiles)
            // Removed items have left the queue by now; any that could not be removed show again.
            delay(REVEAL_AFTER_COMMIT_MS)
            hiddenIds.update { it - ids }
        }) { hiddenIds.update { it - ids } })
    }
}

internal fun removalLabel(count: Int, deleteFiles: Boolean): String = when {
    deleteFiles && count == 1 -> "Deleted file"
    deleteFiles -> "Deleted $count files"
    count == 1 -> "Removed"
    else -> "Removed $count downloads"
}

private const val REVEAL_AFTER_COMMIT_MS = 2_000L
