package com.theoriacodex.app.codex

import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSaveMemoryTest {
    private val search = query("blue_sky")
    private val other = query("red_sky")

    private fun query(tag: String): Query = emptySearchQuery().withIncludeTermsAsRequired(listOf(SearchTerm(tag)))

    @Test fun `codices saved to from the same search outrank tag relevance, most used first`() {
        val memory = SearchSaveMemory()
        memory.record(search, "a")
        memory.record(search, "b")
        memory.record(search, "b")
        val boosted = memory.boost(search, mapOf("c" to 0.9, "a" to 0.1))
        assertEquals(listOf("b", "a", "c"), boosted.entries.sortedByDescending { it.value }.map { it.key })
    }

    @Test fun `other searches and saves without a search are unaffected`() {
        val memory = SearchSaveMemory()
        memory.record(search, "a")
        memory.record(null, "b")
        val relevance = mapOf("c" to 0.5)
        assertEquals(relevance, memory.boost(other, relevance))
        assertEquals(relevance, memory.boost(null, relevance))
        assertTrue(memory.boost(search, relevance).getValue("a") > 1.0)
    }
}
