package com.theoriacodex.app.recents

import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchSection
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation

enum class RecentsClearTarget(
    val clearedMessage: String,
    val failureSubject: String,
) {
    WATCHED("Watched history cleared", "watched history"),
    CODEX("Codex history cleared", "Codex history"),
    FYP("FYP history cleared", "FYP history"),
    SEARCHES("Search history cleared", "search history"),
    ALL("All history cleared", "all history"),
}

internal class RecentsClearWorkflow(
    private val repository: RecentsRepository,
) {
    suspend fun clear(
        target: RecentsClearTarget,
        watchedPosts: List<RecentPostEntry>,
        codexPosts: List<RecentPostEntry>,
        searches: List<RecentSearchEntry>,
        fypSearches: List<RecentSearchEntry>,
        showActionableFeedback: suspend (message: String, actionLabel: String) -> Boolean,
    ) {
        var removedSearches: List<RecentSearchEntry>
        while (true) {
            val cleared = runCatchingPreservingCancellation { clearTarget(target) }
            if (cleared.isSuccess) {
                removedSearches = cleared.getOrThrow()
                break
            }
            if (!showActionableFeedback("Could not clear ${target.failureSubject}", "Retry")) return
        }

        if (!showActionableFeedback(target.clearedMessage, "Undo")) return
        val watchedSnapshot = when (target) {
            RecentsClearTarget.WATCHED -> watchedPosts
            RecentsClearTarget.CODEX -> codexPosts
            RecentsClearTarget.FYP,
            RecentsClearTarget.SEARCHES -> emptyList()
            RecentsClearTarget.ALL -> watchedPosts + codexPosts
        }
        val searchSnapshot = when (target) {
            RecentsClearTarget.FYP, RecentsClearTarget.SEARCHES -> removedSearches
            RecentsClearTarget.ALL -> searches + fypSearches
            else -> emptyList()
        }
        while (true) {
            val restored = runCatchingPreservingCancellation {
                repository.restoreEntries(watchedSnapshot, searchSnapshot)
            }.isSuccess
            if (restored) return
            if (!showActionableFeedback("Could not restore ${target.failureSubject}", "Retry")) return
        }
    }

    private suspend fun clearTarget(target: RecentsClearTarget): List<RecentSearchEntry> = when (target) {
        RecentsClearTarget.FYP -> repository.clearSearches(RecentSearchSection.FYP)
        RecentsClearTarget.SEARCHES -> repository.clearSearches(RecentSearchSection.SEARCHES)
        RecentsClearTarget.WATCHED -> { repository.clearWatchedPosts(RecentPostSection.WATCHED); emptyList() }
        RecentsClearTarget.CODEX -> { repository.clearWatchedPosts(RecentPostSection.CODEX); emptyList() }
        RecentsClearTarget.ALL -> { repository.clearAll(); emptyList() }
    }
}
