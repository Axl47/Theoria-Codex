package com.theoriacodex.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacetedDomainModelsTest {
    @Test
    fun `legacy query labels upgrade to portable general terms`() {
        val query = Query(
            mode = QueryMode.Unified,
            includeTags = listOf("landscape"),
            excludeTags = listOf("comic"),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        assertEquals(listOf(SearchTerm("landscape")), query.includeTerms)
        assertEquals(listOf(SearchTerm("comic")), query.excludeTerms)
        assertTrue(query.includeTerms.single().isPortableGeneralTag)
    }

    @Test
    fun `typed query terms retain facets while exposing plain labels`() {
        val query = Query(
            mode = QueryMode.Source(SourceKey.NHENTAI),
            includeTerms = listOf(
                SearchTerm("najar", facet = SearchFacet.ARTIST, sourceNamespace = "artist"),
                SearchTerm("x-ray", facet = SearchFacet.TAG, sourceNamespace = "female"),
            ),
            excludeTerms = listOf(
                SearchTerm("the idolmaster", facet = SearchFacet.SERIES, sourceNamespace = "parody"),
            ),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        assertEquals(listOf("najar", "x-ray"), query.includeTags)
        assertEquals(listOf("the idolmaster"), query.excludeTags)
        assertFalse(query.includeTerms.first().isPortableGeneralTag)
    }

    @Test
    fun `portable unified projection keeps only tag terms without a namespace`() {
        val query = Query(
            mode = QueryMode.Unified,
            includeTerms = listOf(
                SearchTerm("landscape"),
                SearchTerm("najar", SearchFacet.ARTIST, "artist"),
                SearchTerm("x-ray", SearchFacet.TAG, "female"),
            ),
            excludeTerms = listOf(
                SearchTerm("lowres"),
                SearchTerm("rei ayanami", SearchFacet.CHARACTER, "character"),
            ),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        val portable = query.portableTermsForUnified()

        assertEquals(listOf(SearchTerm("landscape")), portable.includeTerms)
        assertEquals(listOf(SearchTerm("lowres")), portable.excludeTerms)
    }

    @Test
    fun `legacy post metadata projects into typed compatibility fields`() {
        val creator = CreatorProfile(
            source = SourceKey.PIXIV,
            displayName = "Artist",
            profileId = "42",
        )
        val post = Post(
            id = PostId(SourceKey.PIXIV, "1"),
            preview = ImageRef(url = "preview", localPath = null, mime = "image/jpeg"),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = listOf("landscape", "portrait"),
            rawTags = listOf("landscape", "portrait"),
            authorName = creator.displayName,
            createdAtEpochMs = null,
            creatorProfile = creator,
        )

        assertEquals(
            listOf(PostTaxonomyTerm("landscape"), PostTaxonomyTerm("portrait")),
            post.taxonomy,
        )
        assertEquals(listOf(creator), post.creatorProfiles)
        assertFalse(post.preview.isAnimated)
    }

    @Test
    fun `post metadata retains multiple creators taxonomy and animation`() {
        val creators = listOf(
            CreatorProfile(SourceKey.NHENTAI, "Artist One", profileId = "one"),
            CreatorProfile(SourceKey.NHENTAI, "Artist Two", profileId = "two"),
        )
        val taxonomy = listOf(
            PostTaxonomyTerm("Artist One", SearchFacet.ARTIST, "artist"),
            PostTaxonomyTerm("the idolmaster", SearchFacet.SERIES, "parody"),
        )
        val animated = ImageRef(
            url = "animated.webp",
            localPath = null,
            mime = "image/webp",
            isAnimated = true,
        )
        val post = Post(
            id = PostId(SourceKey.NHENTAI, "2"),
            preview = animated,
            full = animated,
            media = listOf(animated),
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = taxonomy.map(PostTaxonomyTerm::value),
            rawTags = taxonomy.map(PostTaxonomyTerm::value),
            authorName = creators.first().displayName,
            createdAtEpochMs = null,
            creatorProfile = creators.first(),
            taxonomy = taxonomy,
            creatorProfiles = creators,
        )

        assertEquals(taxonomy, post.taxonomy)
        assertEquals(creators, post.creatorProfiles)
        assertTrue(post.media.single().isAnimated)
        assertEquals(
            SearchTerm("Artist One", SearchFacet.ARTIST, "artist"),
            post.taxonomy.first().toSearchTerm(),
        )
    }
}
