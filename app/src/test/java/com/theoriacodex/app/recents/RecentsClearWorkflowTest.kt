package com.theoriacodex.app.recents

import com.theoriacodex.data.repository.InMemoryRecentsRepository
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
        val repository = InMemoryRecentsRepository(clock = { 100L })
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

    private fun recentQuery(): Query = Query(
        mode = QueryMode.Source(SourceKey.PIXIV),
        includeTags = listOf("recent"),
        excludeTags = emptyList(),
        sort = SortMode.NEWEST,
        dateRange = null,
        minScore = null,
    )
}
