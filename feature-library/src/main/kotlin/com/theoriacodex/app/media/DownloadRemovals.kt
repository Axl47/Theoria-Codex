package com.theoriacodex.app.media

import androidx.lifecycle.ViewModel
import com.theoriacodex.app.ui.undo.DeferredRemovals
import com.theoriacodex.app.ui.undo.PendingRemoval
import com.theoriacodex.app.ui.undo.UndoAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

data class DownloadRemoval(val ids: Set<Long>, val deleteFiles: Boolean, val anchor: UndoAnchor)

class DownloadRemovals(
    commit: suspend (ids: Set<Long>, deleteFiles: Boolean) -> Boolean,
    scope: CoroutineScope,
) : ViewModel() {
    private val removals = DeferredRemovals<DownloadRemoval>(scope) { commit(it.ids, it.deleteFiles) }

    val pending: StateFlow<Map<DownloadRemoval, PendingRemoval>> = removals.pending

    fun remove(ids: Set<Long>, deleteFiles: Boolean, anchor: UndoAnchor) {
        if (ids.isNotEmpty()) removals.remove(DownloadRemoval(ids, deleteFiles, anchor), removalLabel(ids.size, deleteFiles))
    }

    fun act(removal: DownloadRemoval) = removals.act(removal)

    fun commitAll() = removals.commitAll()

    override fun onCleared() = removals.commitAll()
}

internal fun removalLabel(count: Int, deleteFiles: Boolean): String = when {
    deleteFiles && count == 1 -> "Deleted file"
    deleteFiles -> "Deleted $count files"
    count == 1 -> "Removed"
    else -> "Removed $count downloads"
}
