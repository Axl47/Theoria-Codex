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
    fun `source scoped tags ignore other sources and rank by frequency then alphabetically`() {
        val posts = listOf(
            post(
                source = SourceKey.PIXIV,
                sourcePostId = "p1",
                tags = listOf("sky", "blue sky", "-blocked", ""),
            ),
            post(
                source = SourceKey.GELBOORU,
                sourcePostId = "g1",
                tags = listOf("sky", "gelbooru only", "gelbooru only"),
            ),
            post(
                source = SourceKey.PIXIV,
                sourcePostId = "p2",
                tags = listOf("sky", "portrait"),
            ),
        )

        val tags = buildSourceScopedCodexSearchTags(
            posts = posts,
            source = SourceKey.PIXIV,
            limit = 3,
        )

        assertEquals(listOf("sky", "blue sky", "portrait"), tags)
    }

    @Test
    fun `source scoped tags dedupe with source aware tag keys per post`() {
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

        val tags = buildSourceScopedCodexSearchTags(
            posts = posts,
            source = SourceKey.GELBOORU,
            limit = 2,
        )

        assertEquals(listOf("blue sky", "zeta"), tags)
    }

    @Test
    fun `source scoped tags return empty when no selected source tags exist`() {
        val posts = listOf(
            post(source = SourceKey.NHENTAI, sourcePostId = "n1", tags = listOf("english")),
        )

        val tags = buildSourceScopedCodexSearchTags(
            posts = posts,
            source = SourceKey.PIXIV,
            limit = 3,
        )

        assertEquals(emptyList<String>(), tags)
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
