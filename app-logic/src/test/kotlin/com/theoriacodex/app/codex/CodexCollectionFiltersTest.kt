package com.theoriacodex.app.codex

import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexCollectionFiltersTest {
    @Test
    fun `source animated and duration filters compose without reordering posts`() {
        val pixivStatic = post(SourceKey.PIXIV, "static")
        val pixivShort = post(SourceKey.PIXIV, "short", animated = true, durationMs = 8_000L)
        val pixivLong = post(SourceKey.PIXIV, "long", animated = true, durationMs = 90_000L)
        val hitomiShort = post(SourceKey.HITOMI, "other", animated = true, durationMs = 8_000L)

        val visible = filterCodexCollectionPosts(
            posts = listOf(pixivStatic, pixivShort, pixivLong, hitomiShort),
            filters = CodexCollectionFilters(
                animatedOnly = true,
                animatedDurationRange = AnimatedDurationRange(minBucket = 1, maxBucket = 2),
                source = SourceKey.PIXIV,
            ),
            unknownAnimatedDurationPolicy = UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS,
        )

        assertEquals(listOf(pixivShort), visible)
    }

    @Test
    fun `language matches typed taxonomy and legacy canonical tags only for supported sources`() {
        val typedEnglish = post(
            SourceKey.HITOMI,
            "typed",
            taxonomy = listOf(PostTaxonomyTerm("english", SearchFacet.LANGUAGE, "language")),
        )
        val legacyEnglish = post(SourceKey.NHENTAI, "legacy", tags = listOf("english"))
        val unsupportedEnglish = post(SourceKey.GELBOORU, "unsupported", tags = listOf("english"))
        val japanese = post(SourceKey.NHENTAI, "japanese", tags = listOf("japanese"))

        val visible = filterCodexCollectionPosts(
            posts = listOf(typedEnglish, legacyEnglish, unsupportedEnglish, japanese),
            filters = CodexCollectionFilters(language = CodexLanguageFilter.ENGLISH),
            unknownAnimatedDurationPolicy = UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS,
        )

        assertEquals(listOf(typedEnglish, legacyEnglish), visible)
    }

    @Test
    fun `full color accepts source normalized tags and rejects unsupported sources`() {
        val nhentai = post(SourceKey.NHENTAI, "nh", tags = listOf("full_color"))
        val hitomi = post(
            SourceKey.HITOMI,
            "hitomi",
            taxonomy = listOf(PostTaxonomyTerm("Full Color")),
        )
        val unsupported = post(SourceKey.PIXIV, "pixiv", tags = listOf("full color"))

        val visible = filterCodexCollectionPosts(
            posts = listOf(nhentai, hitomi, unsupported),
            filters = CodexCollectionFilters(fullColorOnly = true),
            unknownAnimatedDurationPolicy = UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS,
        )

        assertEquals(listOf(nhentai, hitomi), visible)
    }

    @Test
    fun `capability controls require represented nhentai or hitomi sources`() {
        assertFalse(supportsCodexLanguageFilter(setOf(SourceKey.PIXIV, SourceKey.GELBOORU)))
        assertFalse(supportsCodexFullColorFilter(setOf(SourceKey.PIXIV)))
        assertTrue(supportsCodexLanguageFilter(setOf(SourceKey.HITOMI)))
        assertTrue(supportsCodexFullColorFilter(setOf(SourceKey.NHENTAI)))
    }

    private fun post(
        source: SourceKey,
        id: String,
        animated: Boolean = false,
        durationMs: Long? = null,
        tags: List<String> = emptyList(),
        taxonomy: List<PostTaxonomyTerm> = tags.map(::PostTaxonomyTerm),
    ): Post {
        val full = ImageRef(
            url = "https://example.test/$id.${if (animated) "mp4" else "jpg"}",
            localPath = null,
            mime = if (animated) "video/mp4" else "image/jpeg",
        )
        return Post(
            id = PostId(source, id),
            preview = full,
            full = full,
            media = listOf(full),
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = tags,
            rawTags = tags,
            authorName = null,
            createdAtEpochMs = null,
            durationMs = durationMs,
            taxonomy = taxonomy,
        )
    }
}
