package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.NewPostMarks
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.data.repository.markKey
import com.theoriacodex.data.repository.newPostsCheckDue
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.normalizedForMode
import com.theoriacodex.domain.model.SortMode

class SavedSearchNewPostsChecker(
    private val store: UiRestoreRepository,
    private val savedSearches: suspend () -> List<SavedSearchEntry>,
    private val firstPage: suspend (SavedSearchEntry) -> List<Post>?,
) {
    suspend fun checkIfStale(nowEpochMs: Long) {
        val entries = savedSearches()
        val recent = entries.sortedByDescending { it.savedAtEpochMs }.take(MAX_CHECKED_SAVED_SEARCHES)
        val saved = entries.mapTo(mutableSetOf()) { it.id }
        recent.mapConcurrent(concurrency = 2) { entry ->
            if (!claim(entry.id, saved, nowEpochMs)) return@mapConcurrent
            val ids = firstPage(entry.newestFirst())?.map { it.id.markKey() }
            val unchanged = savedSearches().any { it.id == entry.id && it.search == entry.search }
            store.updateSavedSearchNewPosts { state ->
                when {
                    state.attempts[entry.id] != nowEpochMs -> state
                    !unchanged -> state.copy(attempts = state.attempts - entry.id)
                    ids == null -> state
                    else -> {
                        val kept = state.marks.filterKeys { it in saved }
                        state.copy(marks = kept + (entry.id to (kept[entry.id] ?: NewPostMarks()).checked(ids, nowEpochMs)))
                    }
                }
            }
        }
    }

    private suspend fun claim(id: String, saved: Set<String>, nowEpochMs: Long): Boolean {
        var claimed = false
        store.updateSavedSearchNewPosts { state ->
            claimed = newPostsCheckDue(listOf(state.attempts[id]), nowEpochMs)
            if (!claimed) state
            else state.copy(attempts = state.attempts.filterKeys { it in saved } + (id to nowEpochMs))
        }
        return claimed
    }
}

internal fun SavedSearchEntry.newestFirst() =
    copy(search = search.copy(query = search.query.copy(sort = SortMode.NEWEST)))

suspend fun UiRestoreRepository.markSavedSearchesSeen(ids: Set<String>? = null) {
    updateSavedSearchNewPosts { state ->
        state.copy(marks = state.marks.mapValues { (id, marks) -> if (ids == null || id in ids) marks.seen() else marks })
    }
}

suspend fun UiRestoreRepository.markSavedSearchPostsSeen(shown: Set<String>) {
    updateSavedSearchNewPosts { state ->
        if (state.marks.values.none { marks -> marks.newPostIds.any { it in shown } }) state
        else state.copy(marks = state.marks.mapValues { (_, marks) -> marks.seen { it in shown } })
    }
}

suspend fun UiRestoreRepository.resetSavedSearchNewPosts(id: String) {
    updateSavedSearchNewPosts { state -> state.copy(attempts = state.attempts - id, marks = state.marks - id) }
}

class SavedSearchOpenMarks(private val store: UiRestoreRepository) {
    private var waiting: Pair<String, Query>? = null

    fun opened(entry: SavedSearchEntry) {
        waiting = entry.id to entry.search.query.run { normalizedForMode(mode).query }
    }

    suspend fun resultsShown(applied: Query, posts: List<Post>) {
        val (id, query) = waiting?.takeIf { it.second == applied } ?: return
        waiting = null
        val shown = posts.mapTo(mutableSetOf()) { it.id.markKey() }
        store.updateSavedSearchNewPosts { state ->
            state.copy(marks = state.marks.mapValues { (key, marks) -> if (key == id) marks.seen { it in shown } else marks })
        }
    }
}

const val MAX_CHECKED_SAVED_SEARCHES = 8
