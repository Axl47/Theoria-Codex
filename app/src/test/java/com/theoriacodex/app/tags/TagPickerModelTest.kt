package com.theoriacodex.app.tags

import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagPickerModelTest {
    private val source = SourceKey.PIXIV
    private val empty = emptySearchQuery(QueryMode.Source(source))
    private val sea = SearchTerm("sea")

    @Test fun `counts stay within the chip's four character slot`() {
        val shown = listOf(0, 842, 999, 1_000, 1_250, 9_999, 84_321, 999_999, 1_200_000, 84_000_000, 2_100_000_000)
            .map(::compactTagCount)
        assertEquals(listOf("0", "842", "999", "1k", "1.2k", "9.9k", "84k", "999k", "1.2m", "84m", "2.1b"), shown)
        assertTrue(shown.all { it.length <= 4 })
    }

    @Test fun `taps cycle through required, excluded, and cleared`() {
        val required = empty.withTagTap(source, sea, null)
        assertEquals(listOf(sea), required.includeTerms)
        val excluded = required.withTagTap(source, sea, null)
        assertEquals(emptyList<SearchTerm>(), excluded.includeTerms)
        assertEquals(listOf(sea), excluded.excludeTerms)
        assertEquals(empty, excluded.withTagTap(source, sea, null))
    }

    @Test fun `a target group takes only tags of its type`() {
        val query = empty.withTagTap(source, sea, null)
        val snow = SearchTerm("snow")
        assertEquals(
            listOf(SearchTermGroup(listOf(sea, snow))),
            query.withTagTap(source, snow, 0).effectiveIncludeTermGroups,
        )
        val artist = SearchTerm("mika", SearchFacet.ARTIST)
        assertEquals(query, query.withTagTap(source, artist, 0))
    }

    @Test fun `excluding one alternative keeps the rest of its group`() {
        val snow = SearchTerm("snow")
        val query = empty.withIncludeTermGroups(listOf(SearchTermGroup(listOf(sea, snow))))
        val tapped = query.withTagTap(source, snow, null)
        assertEquals(listOf(SearchTermGroup.single(sea)), tapped.effectiveIncludeTermGroups)
        assertEquals(listOf(snow), tapped.excludeTerms)
    }
}
