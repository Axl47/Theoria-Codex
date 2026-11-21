package com.theoriacodex.app.recents

import com.theoriacodex.app.ui.undo.UndoAction
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoOffer
import com.theoriacodex.app.ui.undo.UndoSlot
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the offer for a removed search is drawn: the row it vacated. */
fun recentSearchRemovalAnchor(entry: RecentSearchEntry) = RecentsUndoAnchor.copy(key = "search:${entry.queryHash}")

/**
 * Removes one History entry the user swiped away at once, then offers Undo, which restores that exact
 * entry (time, origin and progress included). A removed search stays in the list as [removedSearch] so its
 * row can draw the offer in place; a removed post tile offers Undo in History's pill, since the grid closes
 * the gap. Offers and their restores run in [scope], which should outlive History (the shell's scope).
 */
class RecentsEntryRemoval(
    private val repository: RecentsRepository,
    private val undo: UndoSlot,
    private val scope: CoroutineScope,
) {
    private val removed = MutableStateFlow<RecentSearchEntry?>(null)

    /** The search whose row currently shows the offer, or null. */
    val removedSearch: StateFlow<RecentSearchEntry?> = removed.asStateFlow()

    fun removeSearch(entry: RecentSearchEntry) = remove(
        anchor = recentSearchRemovalAnchor(entry),
        placeholder = entry,
        delete = { repository.removeSearch(entry.queryHash) },
        restore = { repository.restoreEntries(searches = listOf(entry)) },
    )

    fun removePost(entry: RecentPostEntry) = remove(
        anchor = RecentsUndoAnchor,
        placeholder = null,
        delete = { repository.removeWatchedPost(entry.post.id, entry.section) },
        restore = { repository.restoreEntries(watchedPosts = listOf(entry)) },
    )

    private fun remove(
        anchor: UndoAnchor,
        placeholder: RecentSearchEntry?,
        delete: suspend () -> Unit,
        restore: suspend () -> Unit,
    ) {
        scope.launch {
            if (runCatchingPreservingCancellation { delete() }.isFailure) {
                retry("Could not remove") { remove(anchor, placeholder, delete, restore) }
                return@launch
            }
            removed.value = placeholder
            undo.offer(scope, UndoOffer("Removed", anchor, onExpire = { removed.compareAndSet(placeholder, null) }) {
                restoreOrRetry(restore)
                removed.compareAndSet(placeholder, null)
            })
        }
    }

    private suspend fun restoreOrRetry(restore: suspend () -> Unit) {
        if (runCatchingPreservingCancellation { restore() }.isFailure) retry("Could not restore") { restoreOrRetry(restore) }
    }

    private fun retry(label: String, again: suspend () -> Unit) {
        undo.offer(scope, UndoOffer(label, RecentsUndoAnchor, UndoAction.Retry) { again() })
    }
}
