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
    private val d = codex("d")
    private val likes = Codex(ProfileLibraryIds.likes(profile), "Likes", 0)

    @Test fun `picker order puts containing Codices first, then score, ties in library order (ADR 0078)`() {
        val library = listOf(d, a, c, b)
        val ranked = rankSaveTargets(library, profile, setOf("c"), relevance = mapOf("b" to 0.4))
        assertEquals(listOf("c", "b", "d", "a"), ranked.ordered.ids())
        assertEquals(listOf("d", "a", "c", "b"), rankSaveTargets(library, profile, emptySet()).ordered.ids())
    }

    @Test fun `suggestions skip containing Codices, Likes and exclusions (ADR 0078)`() {
        val ranked = rankSaveTargets(
            listOf(likes, a, b, c), profile, setOf("a"),
            relevance = mapOf("c" to 0.9, "b" to 0.1), excludedCodexIds = setOf("b"),
        )
        assertEquals(listOf("c"), ranked.suggestions.ids())
        assertEquals(listOf("a", "c"), ranked.ordered.ids())
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

    @Test fun `with nothing scored the most recently used Codex is suggested first (ADR 0078)`() {
        val ranked = rankSaveTargets(listOf(a, b, c), profile, emptySet(), lastSavedAt = mapOf("b" to 20L, "c" to 10L))
        assertEquals(listOf("b", "c", "a"), ranked.suggestions.ids())
        assertEquals(listOf("a", "b", "c"), ranked.ordered.ids())
    }

    @Test fun `a Codex saved into minutes ago leads a better tag match, until the run of saves is long over (ADR 0078)`() {
        val now = 10 * HOUR
        val lastSavedAt = mapOf("a" to now - 2 * MINUTE)
        val relevance = mapOf("b" to 0.2)

        assertEquals("a", rankSaveTargets(listOf(a, b), profile, emptySet(), relevance, lastSavedAt = lastSavedAt, nowEpochMs = now).suggestions.first().codexId)
        assertEquals("b", rankSaveTargets(listOf(a, b), profile, emptySet(), relevance, lastSavedAt = lastSavedAt, nowEpochMs = now + 3 * HOUR).suggestions.first().codexId)
    }

    @Test fun `a save fades over time from its starting boost (ADR 0078)`() {
        val now = 10 * HOUR
        val ladder = listOf(0.01, 0.02, 0.05, 0.1, 0.15, 0.2, 0.3, 0.5, 0.95)
        fun beaten(savedAgo: Long) = ladder.count { bRelevance ->
            rankSaveTargets(
                listOf(a, b), profile, emptySet(), mapOf("b" to bRelevance), lastSavedAt = mapOf("a" to now - savedAgo), nowEpochMs = now,
            ).suggestions.first().codexId == "a"
        }

        val ages = listOf(0L, 10 * MINUTE, 30 * MINUTE, HOUR, 3 * HOUR, 24 * HOUR).map(::beaten)

        assertTrue(ages.toString(), ages.first() > 0)
        assertTrue(ages.toString(), ages.first() < ladder.size)
        assertEquals(ages.toString(), ages.sortedDescending(), ages)
        assertTrue(ages.toString(), ages.first() > ages.last())
        assertEquals(ages.toString(), 0, ages.last())
    }

    @Test fun `a recent save never lifts a strong match over a Codex this search already saved into (ADR 0078)`() {
        val now = 10 * HOUR
        val search = emptySearchQuery().withIncludeTermsAsRequired(listOf(SearchTerm("sky")))
        val memory = SearchSaveMemory().apply { record(search, "a") }
        val lastSavedAt = mapOf("b" to now - 2 * MINUTE)

        val ranked = rankSaveTargets(listOf(a, b), profile, emptySet(), mapOf("b" to 0.95), memory, search, lastSavedAt, nowEpochMs = now)
        assertEquals(listOf("a", "b"), ranked.suggestions.ids())
        val picker = rankSaveTargets(listOf(a, b), profile, emptySet(), memory.boost(search, mapOf("b" to 0.95)), lastSavedAt = lastSavedAt, nowEpochMs = now)
        assertEquals(listOf("a", "b"), picker.ordered.ids())
        val justSaved = rankSaveTargets(listOf(a, b), profile, emptySet(), emptyMap(), memory, search, mapOf("b" to now), nowEpochMs = now)
        assertEquals(listOf("a", "b"), justSaved.suggestions.ids())
    }

    private fun codex(id: String) = Codex(id, id.uppercase(), 0)
    private fun List<Codex>.ids() = map(Codex::codexId)

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
