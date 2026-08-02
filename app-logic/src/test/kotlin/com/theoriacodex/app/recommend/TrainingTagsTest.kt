package com.theoriacodex.app.recommend

import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class TrainingTagsTest {
    @Test
    fun `recommendations keep tag facets and ignore search-only taxonomy`() {
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

        assertEquals(listOf("general", "x-ray"), recommendationTagsFor(post))
        assertEquals(listOf("general", "x-ray"), trainingTagsFor(post))
    }

    @Test
    fun `pixiv recommendation training keeps native raw tags instead of translated aliases`() {
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

        assertEquals(listOf("猫"), recommendationTagsFor(post))
        assertEquals(listOf("猫"), trainingTagsFor(post))
    }

    @Test
    fun `query affinity seeds ignore non-tag facets`() {
        val query = Query(
            mode = QueryMode.Source(SourceKey.NHENTAI),
            includeTerms = listOf(
                SearchTerm(value = " general "),
                SearchTerm(value = "x-ray", facet = SearchFacet.TAG, sourceNamespace = "female"),
                SearchTerm(value = "najar", facet = SearchFacet.ARTIST, sourceNamespace = "artist"),
            ),
            excludeTerms = emptyList(),
            sort = SortMode.TOP,
            dateRange = null,
            minScore = null,
        )

        assertEquals(listOf("general", "x-ray"), query.recommendationIncludeTags())
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
