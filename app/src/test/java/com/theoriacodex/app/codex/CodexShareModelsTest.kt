package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexShareModelsTest {
    @Test
    fun `builds share file from posts with stable source ids`() {
        val export = buildCodexShareFile(
            title = "Favorites",
            posts = listOf(samplePost(SourceKey.PIXIV, "42"), samplePost(SourceKey.GELBOORU, "99")),
        )

        assertEquals("Favorites", export.title)
        assertEquals(1, export.version)
        assertEquals(
            listOf(
                CodexSharePost(source = "PIXIV", sourcePostId = "42"),
                CodexSharePost(source = "GELBOORU", sourcePostId = "99"),
            ),
            export.posts,
        )
    }

    @Test
    fun `parses share post ids case-insensitively and rejects invalid entries`() {
        assertEquals(
            PostId(source = SourceKey.RULE34VIDEO, sourcePostId = "abc"),
            codexSharePostId(CodexSharePost(source = " rule34video ", sourcePostId = " abc ")),
        )
        assertNull(codexSharePostId(CodexSharePost(source = "unknown", sourcePostId = "1")))
        assertNull(codexSharePostId(CodexSharePost(source = "PIXIV", sourcePostId = " ")))
    }

    @Test
    fun `sanitizes export names for filesystem output`() {
        assertEquals("my_codex_2026", sanitizeCodexExportName(" My Codex! 2026 "))
        assertEquals("codex", sanitizeCodexExportName("..."))
    }

    private fun samplePost(source: SourceKey, sourcePostId: String): Post {
        return Post(
            id = PostId(source = source, sourcePostId = sourcePostId),
            preview = ImageRef(url = "https://example.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            media = emptyList(),
            pageUrl = "https://example.com/post/$sourcePostId",
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = null,
        )
    }
}
