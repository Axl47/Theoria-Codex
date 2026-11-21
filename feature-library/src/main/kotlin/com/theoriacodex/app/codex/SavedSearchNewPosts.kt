package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.NewPostMarks
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.data.repository.markKey
import com.theoriacodex.data.repository.newPostsCheckDue
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SortMode
import kotlinx.coroutines.flow.first

/**
 * The app-open check of saved searches: compares the most recently saved searches' newest posts with
 * what was shown before. [firstPage] runs a search read-only and returns null when any part failed,
 * so a flaky source never counts its posts as new. Searches run newest first whatever sort they were
 * saved with, because a random or popularity order shows older posts that nothing uploaded.
 */
class SavedSearchNewPostsChecker(
    private val store: UiRestoreRepository,
    private val firstPage: suspend (SavedSearchEntry) -> List<Post>?,
) {
    /**
     * Checks the newest [MAX_CHECKED_SAVED_SEARCHES], two at a time, unless the last attempt is recent.
     * The attempt is recorded once the check has run, so one cancelled by leaving the app can run again.
     */
    suspend fun checkIfStale(entries: List<SavedSearchEntry>, nowEpochMs: Long) {
        val recent = entries.sortedByDescending { it.savedAtEpochMs }.take(MAX_CHECKED_SAVED_SEARCHES)
        val attempted = store.observeSavedSearchNewPosts().first().attemptedAtEpochMs
        if (recent.isEmpty() || !newPostsCheckDue(listOf(attempted), nowEpochMs)) return
        val saved = entries.mapTo(mutableSetOf()) { it.id }
        recent.mapConcurrent(concurrency = 2) { entry ->
            val ids = firstPage(entry.newestFirst())?.map { it.id.markKey() } ?: return@mapConcurrent
            store.updateSavedSearchNewPosts { state ->
                val kept = state.marks.filterKeys { it in saved }
                state.copy(marks = kept + (entry.id to (kept[entry.id] ?: NewPostMarks()).checked(ids, nowEpochMs)))
            }
        }
        store.updateSavedSearchNewPosts { it.copy(attemptedAtEpochMs = nowEpochMs) }
    }
}

private fun SavedSearchEntry.newestFirst() =
    copy(search = search.copy(query = search.query.copy(sort = SortMode.NEWEST)))

/** Opening saved searches shows their latest posts: [ids] of them, or all when null. */
suspend fun UiRestoreRepository.markSavedSearchesSeen(ids: Set<String>? = null) {
    updateSavedSearchNewPosts { state ->
        state.copy(marks = state.marks.mapValues { (id, marks) -> if (ids == null || id in ids) marks.seen() else marks })
    }
}

/** A feed showed [shown] ([markKey]s), so those of the searches' latest posts are no longer new. */
suspend fun UiRestoreRepository.markSavedSearchPostsSeen(shown: Set<String>) {
    updateSavedSearchNewPosts { state ->
        state.copy(marks = state.marks.mapValues { (_, marks) -> marks.seen { it in shown } })
    }
}

const val MAX_CHECKED_SAVED_SEARCHES = 8
