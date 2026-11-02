package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.domain.model.Codex
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexLibrarySortingTest {
    private val likes = Codex("likes", "Likes", 0)
    private val manual = listOf(
        Codex("b", "beta", 30), likes, Codex("a", "Alpha", 10), Codex("c", "Gamma", 20), Codex("t", "gamma", 20),
    )
    private val counts = mapOf("a" to 5, "b" to 1, "t" to 5, "likes" to 99)

    private fun sorted(sort: CodexLibrarySort) =
        sortCodicesForLibrary(manual, sort, counts, "likes").map(Codex::codexId)

    @Test fun `manual keeps the stored order including the Likes slot`() {
        assertEquals(listOf("b", "likes", "a", "c", "t"), sorted(CodexLibrarySort.MANUAL))
    }

    @Test fun `automatic sorts pin Likes and keep manual order on ties`() {
        assertEquals(listOf("likes", "a", "b", "c", "t"), sorted(CodexLibrarySort.NAME_ASCENDING))
        assertEquals(listOf("likes", "c", "t", "b", "a"), sorted(CodexLibrarySort.NAME_DESCENDING))
        assertEquals(listOf("likes", "b", "c", "t", "a"), sorted(CodexLibrarySort.NEWEST))
        assertEquals(listOf("likes", "a", "c", "t", "b"), sorted(CodexLibrarySort.OLDEST))
        assertEquals(listOf("likes", "a", "t", "b", "c"), sorted(CodexLibrarySort.MOST_ITEMS))
        assertEquals(listOf("likes", "c", "b", "a", "t"), sorted(CodexLibrarySort.FEWEST_ITEMS))
    }
}
