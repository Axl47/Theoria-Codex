package com.theoriacodex.app.codex

import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.SearchTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveTargetRankingTest {
    private val profile = "profile-main"
    private val a = codex("a")
    private val b = codex("b")
    private val c = codex("c")
    private val likes = Codex(ProfileLibraryIds.likes(profile), "Likes", 0)

    @Test fun `picker order puts containing Codices first, then score, ties in library order`() {
        val ranked = rankSaveTargets(listOf(a, b, c), profile, setOf("c"), relevance = mapOf("b" to 0.4))
        assertEquals(listOf("c", "b", "a"), ranked.ordered.ids())
        assertEquals(listOf("a", "b", "c"), rankSaveTargets(listOf(a, b, c), profile, emptySet()).ordered.ids())
    }

    @Test fun `suggestions skip containing Codices, Likes and exclusions`() {
        val ranked = rankSaveTargets(
            listOf(likes, a, b, c), profile, setOf("a"),
            relevance = mapOf("c" to 0.9, "b" to 0.1), excludedCodexIds = setOf("b"),
        )
        assertEquals(listOf("c"), ranked.suggestions.ids())
        assertEquals(listOf("a", "c"), ranked.ordered.ids())
        assertTrue(ranked.inAnyCodex)
    }

    @Test fun `in any Codex ignores Likes but counts an excluded Codex`() {
        assertFalse(rankSaveTargets(listOf(likes, a), profile, setOf(likes.codexId)).inAnyCodex)
        assertTrue(rankSaveTargets(listOf(a, b), profile, setOf("a"), excludedCodexIds = setOf("a")).inAnyCodex)
    }

    @Test fun `this session's saves from the same search outrank tag relevance`() {
        val search = emptySearchQuery().withIncludeTermsAsRequired(listOf(SearchTerm("sky")))
        val memory = SearchSaveMemory().apply { record(search, "a") }
        val ranked = rankSaveTargets(listOf(a, b), profile, emptySet(), mapOf("b" to 0.9), memory, search)
        assertEquals(listOf("a", "b"), ranked.suggestions.ids())
    }

    @Test fun `with nothing scored the most recently used Codex is suggested first`() {
        val ranked = rankSaveTargets(listOf(a, b, c), profile, emptySet(), lastSavedAt = mapOf("b" to 20L, "c" to 10L))
        assertEquals(listOf("b", "c", "a"), ranked.suggestions.ids())
        assertEquals(listOf("a", "b", "c"), ranked.ordered.ids())
    }

    private fun codex(id: String) = Codex(id, id.uppercase(), 0)
    private fun List<Codex>.ids() = map(Codex::codexId)
}
