package com.theoriacodex.app.recents

import com.theoriacodex.app.ui.undo.UndoAction
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentsClearWorkflowTest {
    @Test
    fun `all clear uses contextual feedback and undo restores exact history`() = runTest {
        var now = 100L
        val repository = InMemoryRecentsRepository(clock = { now++ })
        repository.recordWatchedPost(testPost(sourcePostId = "watched"), ViewerStreamSource.SEARCH, "search")
        repository.recordWatchedPost(testPost(sourcePostId = "codex"), ViewerStreamSource.CODEX, "codex")
        repository.recordSearch(recentQuery().copy(mode = QueryMode.Unified), "unified")
        repository.recordSearch(recentQuery(), "query")
        val watched = repository.observeWatchedPosts().first()
        val searches = repository.observeSearches().first()
        val feedback = mutableListOf<Pair<String, String>>()

        RecentsClearWorkflow(repository).clear(
            target = RecentsClearTarget.ALL,
            watchedPosts = watched.filter { it.section == RecentPostSection.WATCHED },
            codexPosts = watched.filter { it.section == RecentPostSection.CODEX },
            searches = searches,
            showActionableFeedback = { message, action ->
                feedback += message to action.label
                assertEquals(emptyList<RecentPostEntry>(), repository.observeWatchedPosts().first())
                assertEquals(emptyList<RecentSearchEntry>(), repository.observeSearches().first())
                true
            },
        )

        assertEquals(listOf("All history cleared" to "Undo"), feedback)
        assertEquals(watched, repository.observeWatchedPosts().first())
        assertEquals(searches, repository.observeSearches().first())
    }

    @Test
    fun `dismissed watched undo leaves other history intact`() = runTest {
        var now = 100L
        val repository = InMemoryRecentsRepository(clock = { now++ })
        repository.recordWatchedPost(testPost(sourcePostId = "watched"), ViewerStreamSource.SEARCH, null)
        repository.recordWatchedPost(testPost(sourcePostId = "codex"), ViewerStreamSource.CODEX, null)
        repository.recordSearch(recentQuery(), "query")
        val watched = repository.observeWatchedPosts().first()

        RecentsClearWorkflow(repository).clear(
            target = RecentsClearTarget.WATCHED,
            watchedPosts = watched.filter { it.section == RecentPostSection.WATCHED },
            codexPosts = watched.filter { it.section == RecentPostSection.CODEX },
            searches = repository.observeSearches().first(),
            showActionableFeedback = { message, action ->
                assertEquals("Watched history cleared", message)
                assertEquals(UndoAction.Undo, action)
                false
            },
        )

        assertEquals(
            listOf(RecentPostSection.CODEX),
            repository.observeWatchedPosts().first().map { it.section },
        )
        assertEquals(1, repository.observeSearches().first().size)
    }

    @Test
    fun `failed clear and failed Undo retry without clearing unrelated activity`() = runTest {
        var now = 100L
        val stored = InMemoryRecentsRepository(clock = { now++ })
        stored.recordWatchedPost(testPost(sourcePostId = "watched"), ViewerStreamSource.SEARCH, null)
        stored.recordSearch(recentQuery(), "manual")
        val original = stored.observeSearches().first()
        val watched = stored.observeWatchedPosts().first()
        var clears = 0
        var restores = 0
        val repository = object : RecentsRepository by stored {
            override suspend fun clearSearches(): List<RecentSearchEntry> {
                clears++
                if (clears == 1) error("storage unavailable")
                return stored.clearSearches()
            }
            override suspend fun restoreEntries(watchedPosts: List<RecentPostEntry>, searches: List<RecentSearchEntry>) {
                restores++
                if (restores == 1) error("storage unavailable")
                stored.restoreEntries(watchedPosts, searches)
            }
        }
        val messages = mutableListOf<Pair<String, String>>()
        RecentsClearWorkflow(repository).clear(RecentsClearTarget.SEARCHES, emptyList(), emptyList(), original) { message, action ->
            messages += message to action.label
            assertEquals(if (clears == 1) original else emptyList<RecentSearchEntry>(), stored.observeSearches().first())
            true
        }
        assertEquals(
            listOf("Could not clear search history" to "Retry", "Search history cleared" to "Undo",
                "Could not restore search history" to "Retry"), messages,
        )
        assertEquals(2, clears)
        assertEquals(2, restores)
        assertEquals(original, stored.observeSearches().first())
        assertEquals(watched, stored.observeWatchedPosts().first())
    }

    @Test
    fun `Searches Undo restores the actual deletion snapshot`() = runTest {
        for (undo in listOf(false, true)) {
            var now = 100L
            val repository = InMemoryRecentsRepository(clock = { now++ })
            repository.recordSearch(recentQuery().copy(mode = QueryMode.Unified), "unified")
            val shown = repository.observeSearches().first()
            repository.recordSearch(recentQuery(), "new-manual")
            val original = repository.observeSearches().first()
            RecentsClearWorkflow(repository).clear(RecentsClearTarget.SEARCHES, emptyList(), emptyList(), shown) { _, _ ->
                assertEquals(emptyList<RecentSearchEntry>(), repository.observeSearches().first())
                undo
            }
            assertEquals(if (undo) original else emptyList(), repository.observeSearches().first())
        }
    }

    private fun recentQuery(): Query = Query(
        mode = QueryMode.Source(SourceKey.PIXIV),
        includeTags = listOf("recent"),
        excludeTags = emptyList(),
        sort = SortMode.NEWEST,
        dateRange = null,
        minScore = null,
    )
}
