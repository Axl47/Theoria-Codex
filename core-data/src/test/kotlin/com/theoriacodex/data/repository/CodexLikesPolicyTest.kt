package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Codex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexLikesPolicyTest {
    @Test
    fun `Codex naming is trimmed defaulted unique and exclusion aware`() {
        val existing = listOf(
            codex("first", "Favorites"),
            codex("second", "favorites 2"),
            codex("system", "Likes"),
        )

        assertEquals("Codex", CodexLikesPolicy.resolveUniqueCodexName("  ", existing))
        assertEquals("favorites 3", CodexLikesPolicy.resolveUniqueCodexName(" favorites ", existing))
        assertEquals(
            " Favorites ".trim(),
            CodexLikesPolicy.resolveUniqueCodexName(" Favorites ", existing, excludeCodexId = "first"),
        )
    }

    @Test
    fun `like identity and tags use one canonical normalization`() {
        assertEquals("profile-main", CodexLikesPolicy.normalizeProfileId(" profile-main "))
        assertEquals(
            listOf("Tag", "Other"),
            CodexLikesPolicy.normalizeLikedTags(listOf(" Tag ", "tag", "", " Other ", "OTHER")),
        )
    }

    @Test
    fun `complete Codex order accepts only an exact permutation`() {
        val current = listOf(codex("a", "A"), codex("b", "B"), codex("c", "C"))

        assertEquals(
            listOf("c", "a", "b"),
            CodexLikesPolicy.resolveCompleteCodexOrder(current, listOf("c", "a", "b"))
                ?.map(Codex::codexId),
        )
        assertNull(CodexLikesPolicy.resolveCompleteCodexOrder(current, listOf("a", "b")))
        assertNull(CodexLikesPolicy.resolveCompleteCodexOrder(current, listOf("a", "a", "c")))
        assertNull(CodexLikesPolicy.resolveCompleteCodexOrder(current, listOf("a", "b", "unknown")))
    }

    private fun codex(id: String, name: String): Codex {
        return Codex(codexId = id, name = name, createdAtEpochMs = 1L)
    }
}
