package com.theoriacodex.app.search.state

import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.data.repository.SearchScrollState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryTest {
    @Test fun `settling a different search moves the last one back with the place it was left at`() {
        val history = SearchQueryHistory()
            .settled(entry("a"))
            .leaving(SearchScrollState(12, 34))
            .settled(entry("b"))
        assertEquals(listOf(entry("a", SearchScrollState(12, 34))), history.back)
        assertEquals(entry("b"), history.current)
        assertEquals(history, history.settled(entry("b", SearchScrollState(5, 6))))
    }

    @Test fun `back and forward restore each place and a new search drops forward steps`() {
        val history = SearchQueryHistory().settled(entry("a")).leaving(SearchScrollState(1, 2)).settled(entry("b"))
        val back = checkNotNull(history.stepBack(entry("b", SearchScrollState(7, 8))))
        assertEquals(entry("a", SearchScrollState(1, 2)), back.target)
        assertEquals(listOf(entry("b", SearchScrollState(7, 8))), back.history.forward)
        assertFalse(back.history.canStepBack(entry("a")))
        assertTrue(back.history.canStepForward)

        val forward = checkNotNull(back.history.stepForward(entry("a", SearchScrollState(3, 4))))
        assertEquals(entry("b", SearchScrollState(7, 8)), forward.target)
        assertEquals(listOf(entry("a", SearchScrollState(3, 4))), forward.history.back)
        assertFalse(forward.history.canStepForward)

        val branched = back.history.settled(entry("c"))
        assertEquals(emptyList<SearchHistoryEntry>(), branched.forward)
        assertEquals(listOf(entry("a", SearchScrollState(1, 2))), branched.back)
    }

    @Test fun `a step back from an unsettled search returns to the settled one and leaves no trace`() {
        val history = SearchQueryHistory().settled(entry("a")).leaving(SearchScrollState(9, 1))
        assertTrue(history.canStepBack(entry("live")))
        val step = checkNotNull(history.stepBack(entry("live")))
        assertEquals(entry("a", SearchScrollState(9, 1)), step.target)
        assertEquals(history, step.history)
        assertNull(history.stepBack(entry("a")))
    }

    @Test fun `history keeps a bounded number of back steps`() {
        var history = SearchQueryHistory()
        repeat(30) { history = history.settled(entry("q$it")) }
        assertEquals(SearchQueryHistory.LIMIT, history.back.size)
        assertEquals(entry("q9"), history.back.first())
    }

    private fun entry(tag: String, scroll: SearchScrollState = SearchScrollState(0, 0)) = SearchHistoryEntry(
        emptySearchQuery().withIncludeTermsAsRequired(listOf(SearchTerm(tag))), SearchSourceScope.GlobalUnified, scroll,
    )
}
