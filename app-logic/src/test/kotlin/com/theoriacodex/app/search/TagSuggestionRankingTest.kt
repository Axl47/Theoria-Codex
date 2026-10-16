package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class TagSuggestionRankingTest {
    @Test
    fun `exact and prefix relevance beat incomparable counts`() {
        val ranked = rankTagSuggestions(
            suggestions = listOf(
                suggestion("wildcat", count = 1_000_000),
                suggestion("catgirl", count = 50),
                suggestion("cat", count = null),
            ),
            prefix = "cat",
            limit = 10,
        )

        assertEquals(listOf("cat", "catgirl", "wildcat"), ranked.map(TagSuggestion::text))
    }

    @Test
    fun `alternate text matches while native provider value is retained`() {
        val pixiv = suggestion(text = "初音ミク", alternateText = "Hatsune Miku")

        assertEquals(listOf(pixiv), rankTagSuggestions(listOf(pixiv), "hatsune", 10))
        assertEquals("初音ミク", rankTagSuggestions(listOf(pixiv), "miku", 10).single().text)
    }

    @Test
    fun `null-count provider order is stable`() {
        val providerOrder = listOf(suggestion("blue archive"), suggestion("blue eyes"))

        assertEquals(providerOrder, rankTagSuggestions(providerOrder, "blue", 10))
    }

    @Test
    fun `unified ranking interleaves providers within each relevance band`() {
        val ranked = rankUnifiedTagSuggestions(
            suggestionsBySource = listOf(
                SourceKey.GELBOORU to listOf(suggestion("blue_hair", 100), suggestion("blue_eyes", 90)),
                SourceKey.PIXIV to listOf(suggestion("blue archive"), suggestion("blue sky")),
            ),
            prefix = "blue",
            limit = 4,
        )

        assertEquals(
            listOf("blue_hair", "blue archive", "blue_eyes", "blue sky"),
            ranked.map(TagSuggestion::text),
        )
    }

    @Test
    fun `trending interleave preserves provider order without cross-source count bias`() {
        val combined = interleaveTagSuggestions(
            suggestionsBySource = listOf(
                SourceKey.GELBOORU to listOf(suggestion("gel_one", 1000), suggestion("gel_two", 900)),
                SourceKey.PIXIV to listOf(suggestion("pixiv_one"), suggestion("pixiv_two")),
            ),
            limit = 4,
        )

        assertEquals(
            listOf("gel_one", "pixiv_one", "gel_two", "pixiv_two"),
            combined.map(TagSuggestion::text),
        )
    }

    @Test
    fun `blank input sorts known counts descending before limiting and preserves ties`() {
        val suggestions = listOf(
            suggestion("unknown first"),
            suggestion("small", 2),
            suggestion("popular first", 100),
            suggestion("popular second", 100),
            suggestion("zero", 0),
            suggestion("unknown second"),
        )

        assertEquals(
            listOf("popular first", "popular second", "small", "zero", "unknown first", "unknown second"),
            rankTagSuggestions(suggestions, "", 10).map(TagSuggestion::text),
        )
        assertEquals(
            listOf("popular first", "popular second"),
            rankTagSuggestions(suggestions, "", 2).map(TagSuggestion::text),
        )
    }

    @Test
    fun `featured facets sort by count and retain provider order without counts`() {
        val suggestions = listOf(
            FacetedTagSuggestion("unknown first", SearchFacet.TAG, count = null),
            FacetedTagSuggestion("small", SearchFacet.TAG, count = 2),
            FacetedTagSuggestion("popular", SearchFacet.TAG, count = 100),
            FacetedTagSuggestion("unknown second", SearchFacet.TAG, count = null),
        )

        assertEquals(
            listOf("popular", "small", "unknown first", "unknown second"),
            rankFacetedTagSuggestions(suggestions, "", 10).map(FacetedTagSuggestion::text),
        )
    }

    private fun suggestion(
        text: String,
        count: Int? = null,
        alternateText: String? = null,
    ) = TagSuggestion(text = text, type = "tag", count = count, alternateText = alternateText)
}
