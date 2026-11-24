package com.theoriacodex.domain.query

import com.theoriacodex.domain.adapter.AnimatedSearchTerm
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTermsTest {
    private val danbooruLike = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = false,
        supportsExcludeTagsServerSide = true,
        searchTermLimit = SearchTermLimit(maxTerms = 2, rankedSortUsesTerm = true),
        animatedTerm = AnimatedSearchTerm(
            SearchTerm("animated"),
            overLimit = SearchTerm("filetype:gif,mp4,webm,zip"),
        ),
    )

    private fun query(vararg tags: String, sort: SortMode = SortMode.NEWEST, media: MediaFilter = MediaFilter.ANIMATED) =
        Query(QueryMode.Source(SourceKey.DANBOORU), tags.toList(), emptyList(), sort, null, null).copy(media = media)

    @Test
    fun `an animated search gains the source's term while it fits the limit (ADR 0008)`() {
        assertEquals(listOf("cat", "animated"), query("cat").withMediaTerms(danbooruLike).includeTags)
    }

    @Test
    fun `a full term limit falls back to the no-cost term (ADR 0008)`() {
        assertEquals(listOf("cat", "dog", "filetype:gif,mp4,webm,zip"), query("cat", "dog").withMediaTerms(danbooruLike).includeTags)
        assertEquals(
            listOf("cat", "filetype:gif,mp4,webm,zip"),
            query("cat", sort = SortMode.TOP).withMediaTerms(danbooruLike).includeTags,
        )
        val noFallback = danbooruLike.copy(animatedTerm = AnimatedSearchTerm(SearchTerm("animated")))
        assertEquals(listOf("cat", "dog"), query("cat", "dog").withMediaTerms(noFallback).includeTags)
    }

    @Test
    fun `nothing is added when unasked, already asked, or the source has no term (ADR 0011)`() {
        assertEquals(listOf("cat"), query("cat", media = MediaFilter.ANY).withMediaTerms(danbooruLike).includeTags)
        assertEquals(listOf("Animated"), query("Animated").withMediaTerms(danbooruLike).includeTags)
        assertEquals(listOf("cat"), query("cat").withMediaTerms(danbooruLike.copy(animatedTerm = null)).includeTags)
    }

    @Test
    fun `an animated alternative in an OR group still gets the term (ADR 0011)`() {
        val either = query().withIncludeTermGroups(listOf(SearchTermGroup(listOf(SearchTerm("animated"), SearchTerm("landscape")))))

        assertEquals(listOf("animated", "landscape", "animated"), either.withMediaTerms(danbooruLike).includeTags)
    }
}
