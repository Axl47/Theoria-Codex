package com.theoriacodex.app.recents

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.undo.UndoSlot
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentsEntryRemovalTest {
    private var now = 100L
    private val repository = InMemoryRecentsRepository(clock = { now++ })
    private val undo = UndoSlot()

    @Test
    fun `a swiped search leaves at once, keeps its row for the offer, and undo restores it exactly`() = runTest {
        repository.recordSearch(query("kept"), "kept")
        repository.recordSearch(query("gone"), "gone")
        val before = repository.observeSearches().first()
        val removal = removal()

        removal.removeSearch(before.first { it.queryHash == "gone" })
        runCurrent()

        assertEquals(listOf("kept"), repository.observeSearches().first().map { it.queryHash })
        assertEquals("gone", removal.removedSearch.value?.queryHash)
        val offer = checkNotNull(undo.offer.value)
        assertEquals(recentSearchRemovalAnchor(before.first()), offer.anchor)

        undo.act(offer)
        runCurrent()

        assertEquals(before, repository.observeSearches().first())
        assertNull(removal.removedSearch.value)
    }

    @Test
    fun `a swiped post leaves only its own section and stays gone once the offer expires`() = runTest {
        val post = testPost(sourcePostId = "post")
        repository.recordWatchedPost(post, ViewerStreamSource.SEARCH, "q", RecentPostSection.WATCHED)
        repository.recordWatchedPost(post, ViewerStreamSource.CODEX, "c", RecentPostSection.CODEX)
        val watched = repository.observeWatchedPosts().first().first { it.section == RecentPostSection.WATCHED }

        removal().removePost(watched)
        runCurrent()
        assertEquals(RecentsUndoAnchor, undo.offer.value?.anchor)
        advanceTimeBy(10_000)
        runCurrent()

        assertNull(undo.offer.value)
        assertEquals(listOf(RecentPostSection.CODEX), repository.observeWatchedPosts().first().map { it.section })
    }

    private fun TestScope.removal() = RecentsEntryRemoval(repository, undo, backgroundScope)

    private fun query(tag: String) = Query(
        mode = QueryMode.Source(SourceKey.PIXIV), includeTags = listOf(tag), excludeTags = emptyList(),
        sort = SortMode.NEWEST, dateRange = null, minScore = null,
    )
}
