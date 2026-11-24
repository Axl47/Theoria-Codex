package com.theoriacodex.app.recommend

import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class TrainingTagsTest {
    @Test
    fun `recommendations keep tag facets and ignore search-only taxonomy (ADR 0088)`() {
        val post = Post(
            id = PostId(SourceKey.NHENTAI, "1"),
            preview = ImageRef(url = "https://example.com/1.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = listOf("general", "najar", "english", "x-ray"),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            taxonomy = listOf(
                PostTaxonomyTerm(value = "general"),
                PostTaxonomyTerm(value = "x-ray", facet = SearchFacet.TAG, sourceNamespace = "female"),
                PostTaxonomyTerm(value = "najar", facet = SearchFacet.ARTIST, sourceNamespace = "artist"),
                PostTaxonomyTerm(value = "english", facet = SearchFacet.LANGUAGE, sourceNamespace = "language"),
            ),
        )

        assertEquals(listOf("general", "x-ray"), trainingTagsFor(post))
    }

    @Test
    fun `a Like keeps every tag, however far down the provider's alphabetical list (ADR 0088)`() {
        val tags = (1..60).map { "tag_%02d".format((it * 37) % 60 + 1) }
        val listed = tags + listOf(tags[3].uppercase(), tags[0])
        val post = Post(
            id = PostId(SourceKey.GELBOORU, "7"),
            preview = ImageRef(url = "https://example.com/7.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = listed,
            rawTags = listed,
            authorName = null,
            createdAtEpochMs = null,
        )

        assertEquals(tags, trainingTagsFor(post))
    }

    @Test
    fun `a tag held under two namespaces is taught once (ADR 0088)`() {
        val post = Post(
            id = PostId(SourceKey.GELBOORU, "9"),
            preview = ImageRef(url = "https://example.com/9.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = listOf("wolf", "moth"),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            taxonomy = listOf(
                PostTaxonomyTerm(value = "wolf", facet = SearchFacet.TAG, sourceNamespace = "general"),
                PostTaxonomyTerm(value = "wolf", facet = SearchFacet.TAG, sourceNamespace = "character"),
                PostTaxonomyTerm(value = "moth", facet = SearchFacet.TAG, sourceNamespace = "general"),
            ),
        )

        assertEquals(listOf("wolf", "moth"), trainingTagsFor(post))
    }

    @Test
    fun `a post with raw tags and no typed taxonomy teaches its raw tags (ADR 0088)`() {
        val post = Post(
            id = PostId(SourceKey.GELBOORU, "8"),
            preview = ImageRef(url = "https://example.com/8.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = listOf("moth", "lamp"),
            authorName = null,
            createdAtEpochMs = null,
        )

        assertEquals(listOf("moth", "lamp"), trainingTagsFor(post))
    }

    @Test
    fun `pixiv recommendation training keeps native raw tags instead of translated aliases (ADR 0088)`() {
        val post = Post(
            id = PostId(SourceKey.PIXIV, "42"),
            preview = ImageRef(url = "https://example.com/42.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = listOf("猫", "cat"),
            rawTags = listOf("猫"),
            authorName = null,
            createdAtEpochMs = null,
            taxonomy = listOf(
                PostTaxonomyTerm(value = "猫"),
                PostTaxonomyTerm(value = "cat"),
            ),
        )

        assertEquals(listOf("猫"), trainingTagsFor(post))
    }

    @Test
    fun `typed suggestion metadata keeps non-tag taxonomy out of recommendation fallbacks`() {
        assertEquals(
            listOf("tag", "female"),
            listOf(
                TagSuggestion("tag", type = "tag", count = null),
                TagSuggestion("artist", type = "artist", count = null),
                TagSuggestion("series", type = "parody", count = null),
                TagSuggestion("female", type = "female", count = null),
            )
                .filter(TagSuggestion::isRecommendationTagSuggestion)
                .map(TagSuggestion::text),
        )
    }
}
