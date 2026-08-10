package com.theoriacodex.app.recents

import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.RecentSearchKind
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
        repository.recordSearch(
            recentQuery().copy(mode = QueryMode.Unified),
            "for_you:seed",
            RecentSearchKind.FYP,
            listOf(SourceKey.PIXIV),
        )
        repository.recordSearch(recentQuery(), "query")
        val watched = repository.observeWatchedPosts().first()
        val searches = repository.observeSearches().first()
        val feedback = mutableListOf<Pair<String, String>>()

        RecentsClearWorkflow(repository).clear(
            target = RecentsClearTarget.ALL,
            watchedPosts = watched.filter { it.section == RecentPostSection.WATCHED },
            codexPosts = watched.filter { it.section == RecentPostSection.CODEX },
            searches = searches.filterNot { it.kind == RecentSearchKind.FYP },
            fypSearches = searches.filter { it.kind == RecentSearchKind.FYP },
            showActionableFeedback = { message, action ->
                feedback += message to action
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
            fypSearches = emptyList(),
            showActionableFeedback = { message, action ->
                assertEquals("Watched history cleared", message)
                assertEquals("Undo", action)
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
    fun `FYP clear undo restores recommendation searches without touching applied searches`() = runTest {
        var now = 100L
        val repository = InMemoryRecentsRepository(clock = { now++ })
        repository.recordSearch(recentQuery(), "manual")
        repository.recordSearch(
            query = recentQuery().copy(mode = QueryMode.Unified),
            queryHash = "for_you:seed",
            kind = RecentSearchKind.FYP,
            sources = listOf(SourceKey.PIXIV),
        )
        val searches = repository.observeSearches().first()
        val fyp = searches.filter { it.kind == RecentSearchKind.FYP }

        RecentsClearWorkflow(repository).clear(
            target = RecentsClearTarget.FYP,
            watchedPosts = emptyList(),
            codexPosts = emptyList(),
            searches = searches.filterNot { it.kind == RecentSearchKind.FYP },
            fypSearches = fyp,
            showActionableFeedback = { message, action ->
                assertEquals("FYP history cleared", message)
                assertEquals("Undo", action)
                true
            },
        )

        assertEquals(searches, repository.observeSearches().first())
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
