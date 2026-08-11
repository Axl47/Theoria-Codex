package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `automatic tags are normalized source aware and reversible`() {
        val current = listOf(
            CodexAutomaticTag(SourceKey.GELBOORU, "blue sky"),
            CodexAutomaticTag(SourceKey.GELBOORU, "blue_sky"),
            CodexAutomaticTag(SourceKey.PIXIV, " Blue Sky "),
            CodexAutomaticTag(SourceKey.PIXIV, "-blocked"),
        )

        val normalized = CodexLikesPolicy.normalizeAutomaticTags(current)
        val removed = CodexLikesPolicy.setAutomaticTag(
            current = normalized,
            requested = CodexAutomaticTag(SourceKey.GELBOORU, "blue_sky"),
            enabled = false,
        )

        assertEquals(
            listOf(
                CodexAutomaticTag(SourceKey.PIXIV, "Blue Sky"),
                CodexAutomaticTag(SourceKey.GELBOORU, "blue sky"),
            ),
            normalized,
        )
        assertEquals(listOf(CodexAutomaticTag(SourceKey.PIXIV, "Blue Sky")), removed)
    }

    @Test
    fun `automatic tags match any canonical tag only within the same source`() {
        val post = Post(
            id = PostId(SourceKey.PIXIV, "1"),
            preview = ImageRef(null, null, null),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = listOf("blue_sky", "portrait"),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )

        assertEquals(
            true,
            CodexLikesPolicy.postMatchesAnyAutomaticTag(
                post,
                listOf(CodexAutomaticTag(SourceKey.PIXIV, "blue sky")),
            ),
        )
        assertEquals(
            false,
            CodexLikesPolicy.postMatchesAnyAutomaticTag(
                post,
                listOf(CodexAutomaticTag(SourceKey.GELBOORU, "blue sky")),
            ),
        )
    }

    @Test
    fun `automatic tag groups require every group while accepting alternatives`() {
        val post = Post(
            id = PostId(SourceKey.PIXIV, "grouped"),
            preview = ImageRef(null, null, null),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = listOf("tag1", "tag4"),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )
        val matching = listOf(
            CodexAutomaticTag(SourceKey.PIXIV, "tag1", groupIndex = 0),
            CodexAutomaticTag(SourceKey.PIXIV, "tag2", groupIndex = 0),
            CodexAutomaticTag(SourceKey.PIXIV, "tag3", groupIndex = 1),
            CodexAutomaticTag(SourceKey.PIXIV, "tag4", groupIndex = 1),
        )

        assertTrue(CodexLikesPolicy.postMatchesAutomaticTagGroups(post, matching))
        assertFalse(
            CodexLikesPolicy.postMatchesAutomaticTagGroups(
                post,
                matching.filterNot { tag -> tag.groupIndex == 1 && tag.tag == "tag4" },
            ),
        )
    }

    private fun codex(id: String, name: String): Codex {
        return Codex(codexId = id, name = name, createdAtEpochMs = 1L)
    }
}
