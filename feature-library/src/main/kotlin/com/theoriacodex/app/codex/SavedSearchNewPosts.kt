package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.NewPostMarks
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.data.repository.markKey
import com.theoriacodex.data.repository.newPostsCheckDue
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.flow.first

/**
 * The app-open check of saved searches: compares the most recently saved searches' first pages with
 * what was shown before. [firstPage] runs a search read-only and returns null when any part failed,
 * so a flaky source never counts its posts as new.
 */
class SavedSearchNewPostsChecker(
    private val store: UiRestoreRepository,
    private val firstPage: suspend (SavedSearchEntry) -> List<Post>?,
) {
    /** Checks the newest [MAX_CHECKED_SAVED_SEARCHES], two at a time, unless the last attempt is recent. */
    suspend fun checkIfStale(entries: List<SavedSearchEntry>, nowEpochMs: Long) {
        val recent = entries.sortedByDescending { it.savedAtEpochMs }.take(MAX_CHECKED_SAVED_SEARCHES)
        val attempted = store.observeSavedSearchNewPosts().first().attemptedAtEpochMs
        if (recent.isEmpty() || !newPostsCheckDue(listOf(attempted), nowEpochMs)) return
        store.updateSavedSearchNewPosts { it.copy(attemptedAtEpochMs = nowEpochMs) }
        val saved = entries.mapTo(mutableSetOf()) { it.id }
        recent.mapConcurrent(concurrency = 2) { entry ->
            val ids = firstPage(entry)?.map { it.id.markKey() } ?: return@mapConcurrent
            store.updateSavedSearchNewPosts { state ->
                val kept = state.marks.filterKeys { it in saved }
                state.copy(marks = kept + (entry.id to (kept[entry.id] ?: NewPostMarks()).checked(ids, nowEpochMs)))
            }
        }
    }
}

/** Opening saved searches shows their latest posts: [ids] of them, or all when null. */
suspend fun UiRestoreRepository.markSavedSearchesSeen(ids: Set<String>? = null) {
    updateSavedSearchNewPosts { state ->
        state.copy(marks = state.marks.mapValues { (id, marks) -> if (ids == null || id in ids) marks.seen() else marks })
    }
}

const val MAX_CHECKED_SAVED_SEARCHES = 8
