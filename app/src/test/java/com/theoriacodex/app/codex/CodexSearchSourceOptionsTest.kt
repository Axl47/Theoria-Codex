package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexSearchSourceOptionsTest {
    @Test
    fun `source options include only available sources represented in codex posts`() {
        val posts = listOf(
            post(source = SourceKey.GELBOORU, sourcePostId = "g1"),
            post(source = SourceKey.PIXIV, sourcePostId = "p1"),
            post(source = SourceKey.GELBOORU, sourcePostId = "g2"),
            post(source = SourceKey.IWARA, sourcePostId = "i1"),
        )

        val options = codexSearchSourceOptions(
            posts = posts,
            availableSources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
        )

        assertEquals(
            listOf(
                CodexSearchSourceOption(source = SourceKey.PIXIV, postCount = 1),
                CodexSearchSourceOption(source = SourceKey.GELBOORU, postCount = 2),
            ),
            options,
        )
    }

    @Test
    fun `codex search tag options filters to selected source`() {
        val posts = listOf(
            post(
                source = SourceKey.PIXIV,
                sourcePostId = "p1",
                tags = listOf("sky", "blue sky"),
            ),
            post(
                source = SourceKey.GELBOORU,
                sourcePostId = "g1",
                tags = listOf("gelbooru only"),
            ),
        )

        val options = codexSearchTagOptions(
            posts = posts,
            source = SourceKey.PIXIV,
        )

        assertEquals(
            listOf(
                CodexSearchTagOption(tag = "blue sky", count = 1),
                CodexSearchTagOption(tag = "sky", count = 1),
            ),
            options,
        )
    }

    @Test
    fun `codex search tag options counts source tags by post frequency`() {
        val posts = listOf(
            post(
                source = SourceKey.PIXIV,
                sourcePostId = "p1",
                tags = listOf("sky", "blue sky"),
            ),
            post(
                source = SourceKey.PIXIV,
                sourcePostId = "p2",
                tags = listOf("sky", "portrait"),
            ),
            post(
                source = SourceKey.PIXIV,
                sourcePostId = "p3",
                tags = listOf("portrait"),
            ),
        )

        val options = codexSearchTagOptions(
            posts = posts,
            source = SourceKey.PIXIV,
        )

        assertEquals(
            listOf(
                CodexSearchTagOption(tag = "portrait", count = 2),
                CodexSearchTagOption(tag = "sky", count = 2),
                CodexSearchTagOption(tag = "blue sky", count = 1),
            ),
            options,
        )
    }

    @Test
    fun `codex search tag options dedupes source aware duplicate tags within one post`() {
        val posts = listOf(
            post(
                source = SourceKey.GELBOORU,
                sourcePostId = "g1",
                tags = listOf("blue sky", "blue_sky", "zeta"),
            ),
            post(
                source = SourceKey.GELBOORU,
                sourcePostId = "g2",
                tags = listOf("blue_sky"),
            ),
        )

        val options = codexSearchTagOptions(
            posts = posts,
            source = SourceKey.GELBOORU,
        )

        assertEquals(
            listOf(
                CodexSearchTagOption(tag = "blue sky", count = 2),
                CodexSearchTagOption(tag = "zeta", count = 1),
            ),
            options,
        )
    }

    @Test
    fun `codex search tag options ignores blank and negative tags`() {
        val posts = listOf(
            post(
                source = SourceKey.PIXIV,
                sourcePostId = "p1",
                tags = listOf("", "   ", "-blocked", "valid"),
            ),
        )

        val options = codexSearchTagOptions(
            posts = posts,
            source = SourceKey.PIXIV,
        )

        assertEquals(listOf(CodexSearchTagOption(tag = "valid", count = 1)), options)
    }

    @Test
    fun `codex search tag options sorts by count descending then alphabetically`() {
        val posts = listOf(
            post(source = SourceKey.PIXIV, sourcePostId = "p1", tags = listOf("zeta", "alpha", "beta")),
            post(source = SourceKey.PIXIV, sourcePostId = "p2", tags = listOf("zeta", "alpha")),
            post(source = SourceKey.PIXIV, sourcePostId = "p3", tags = listOf("beta")),
        )

        val options = codexSearchTagOptions(
            posts = posts,
            source = SourceKey.PIXIV,
        )

        assertEquals(
            listOf(
                CodexSearchTagOption(tag = "alpha", count = 2),
                CodexSearchTagOption(tag = "beta", count = 2),
                CodexSearchTagOption(tag = "zeta", count = 2),
            ),
            options,
        )
    }

    @Test
    fun `source scoped top tag helper delegates to codex search tag options`() {
        val posts = listOf(
            post(source = SourceKey.PIXIV, sourcePostId = "p1", tags = listOf("zeta", "alpha")),
            post(source = SourceKey.PIXIV, sourcePostId = "p2", tags = listOf("zeta")),
        )

        val tags = buildSourceScopedCodexSearchTags(
            posts = posts,
            source = SourceKey.PIXIV,
            limit = 1,
        )

        assertEquals(listOf("zeta"), tags)
    }

    private fun post(
        source: SourceKey,
        sourcePostId: String,
        tags: List<String> = emptyList(),
    ): Post {
        return Post(
            id = PostId(source = source, sourcePostId = sourcePostId),
            preview = ImageRef(url = null, localPath = null, mime = null),
            full = null,
            media = emptyList(),
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = tags,
            rawTags = tags,
            authorName = null,
            createdAtEpochMs = null,
        )
    }
}
