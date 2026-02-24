package com.theoriacodex.domain.query

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryStateMachineTest {
    @Test
    fun `from applied starts without pending changes`() {
        val baseQuery = baseQuery()

        val state = SearchQueryState.fromApplied(baseQuery)

        assertEquals(baseQuery, state.draftQuery)
        assertEquals(baseQuery, state.appliedQuery)
        assertFalse(state.hasPendingChanges)
    }

    @Test
    fun `update draft marks state dirty and apply clears pending`() {
        val state = SearchQueryState.fromApplied(baseQuery())

        val updated = state.updateDraft { it.copy(includeTags = it.includeTags + "portrait") }
        assertTrue(updated.hasPendingChanges)

        val applied = updated.applyDraft()
        assertFalse(applied.hasPendingChanges)
        assertEquals(applied.draftQuery, applied.appliedQuery)
    }

    @Test
    fun `reset draft restores applied query`() {
        val state = SearchQueryState.fromApplied(baseQuery())
            .updateDraft { it.copy(sort = SortMode.RANDOM) }

        val reset = state.resetDraft()

        assertFalse(reset.hasPendingChanges)
        assertEquals(baseQuery(), reset.draftQuery)
    }

    private fun baseQuery(): Query {
        return Query(
            mode = QueryMode.Unified,
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }
}
