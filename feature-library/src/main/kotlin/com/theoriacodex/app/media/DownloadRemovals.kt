package com.theoriacodex.app.media

import androidx.lifecycle.ViewModel
import com.theoriacodex.app.ui.undo.DeferredRemovals
import com.theoriacodex.app.ui.undo.PendingRemoval
import com.theoriacodex.app.ui.undo.UndoAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** One download removal: the items it hides, whether it deletes their files, and where the sheet draws its row. */
data class DownloadRemoval(val ids: Set<Long>, val deleteFiles: Boolean, val anchor: UndoAnchor)

/**
 * Removes downloads as deferred destruction ([DeferredRemovals]): they hide at once with Undo at the anchor, and
 * their records (and files, for Delete) go only when the Undo times out, through [commit], which reports whether
 * every item went. Activity-retained, so a rotation keeps pending removals; clearing it commits them in [scope],
 * which must be application-lived.
 */
class DownloadRemovals(
    commit: suspend (ids: Set<Long>, deleteFiles: Boolean) -> Boolean,
    scope: CoroutineScope,
) : ViewModel() {
    private val removals = DeferredRemovals<DownloadRemoval>(scope) { commit(it.ids, it.deleteFiles) }

    /** Removals waiting on Undo, committing, or offering Retry. */
    val pending: StateFlow<Map<DownloadRemoval, PendingRemoval>> = removals.pending

    fun remove(ids: Set<Long>, deleteFiles: Boolean, anchor: UndoAnchor) {
        if (ids.isNotEmpty()) removals.remove(DownloadRemoval(ids, deleteFiles, anchor), removalLabel(ids.size, deleteFiles))
    }

    /** Undo, or Retry after a failure, for [removal]'s row. */
    fun act(removal: DownloadRemoval) = removals.act(removal)

    /** Commits every waiting removal; the sheet calls it when closed. */
    fun commitAll() = removals.commitAll()

    override fun onCleared() = removals.commitAll()
}

internal fun removalLabel(count: Int, deleteFiles: Boolean): String = when {
    deleteFiles && count == 1 -> "Deleted file"
    deleteFiles -> "Deleted $count files"
    count == 1 -> "Removed"
    else -> "Removed $count downloads"
}
