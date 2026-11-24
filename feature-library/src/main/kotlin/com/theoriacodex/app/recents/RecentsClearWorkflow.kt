package com.theoriacodex.app.recents

import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.app.ui.undo.UndoAction
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation

enum class RecentsClearTarget(
    val clearedMessage: String,
    val failureSubject: String,
) {
    WATCHED("Watched history cleared", "watched history"),
    CODEX("Codex history cleared", "Codex history"),
    SEARCHES("Search history cleared", "search history"),
    ALL("All history cleared", "all history"),
}

val RecentsUndoAnchor = UndoAnchor("history")

class RecentsClearWorkflow(
    private val repository: RecentsRepository,
) {
    suspend fun clear(
        target: RecentsClearTarget,
        watchedPosts: List<RecentPostEntry>,
        codexPosts: List<RecentPostEntry>,
        searches: List<RecentSearchEntry>,
        showActionableFeedback: suspend (message: String, action: UndoAction) -> Boolean,
    ) {
        var removedSearches: List<RecentSearchEntry>
        while (true) {
            val cleared = runCatchingPreservingCancellation { clearTarget(target) }
            if (cleared.isSuccess) {
                removedSearches = cleared.getOrThrow()
                break
            }
            if (!showActionableFeedback("Could not clear ${target.failureSubject}", UndoAction.Retry)) return
        }

        if (!showActionableFeedback(target.clearedMessage, UndoAction.Undo)) return
        val watchedSnapshot = when (target) {
            RecentsClearTarget.WATCHED -> watchedPosts
            RecentsClearTarget.CODEX -> codexPosts
            RecentsClearTarget.SEARCHES -> emptyList()
            RecentsClearTarget.ALL -> watchedPosts + codexPosts
        }
        val searchSnapshot = when (target) {
            RecentsClearTarget.SEARCHES -> removedSearches
            RecentsClearTarget.ALL -> searches
            else -> emptyList()
        }
        while (true) {
            val restored = runCatchingPreservingCancellation {
                repository.restoreEntries(watchedSnapshot, searchSnapshot)
            }.isSuccess
            if (restored) return
            if (!showActionableFeedback("Could not restore ${target.failureSubject}", UndoAction.Retry)) return
        }
    }

    private suspend fun clearTarget(target: RecentsClearTarget): List<RecentSearchEntry> = when (target) {
        RecentsClearTarget.SEARCHES -> repository.clearSearches()
        RecentsClearTarget.WATCHED -> { repository.clearWatchedPosts(RecentPostSection.WATCHED); emptyList() }
        RecentsClearTarget.CODEX -> { repository.clearWatchedPosts(RecentPostSection.CODEX); emptyList() }
        RecentsClearTarget.ALL -> { repository.clearAll(); emptyList() }
    }
}
