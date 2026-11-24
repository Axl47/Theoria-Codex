package com.theoriacodex.domain.query

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupedSearchMatchingTest {
    @Test
    fun `search matches tags in its own spelling, which recommendation normalization would change (ADR 0003)`() {
        fun matches(source: SourceKey, tag: String, term: String) = Post(
            id = PostId(source, "1"), preview = ImageRef("https://example.com/1.jpg", null, "image/jpeg"),
            full = null, pageUrl = null, width = null, height = null, canonicalTags = emptyList(),
            rawTags = listOf(tag), authorName = null, createdAtEpochMs = 0,
        ).matchesIncludeTermGroups(
            Query(QueryMode.Unified, listOf(SearchTermGroup.single(SearchTerm(term))), emptyList(), SortMode.NEWEST, null, null),
        )

        assertEquals(
            listOf(true, false, false),
            listOf(
                matches(SourceKey.PIXIV, "Blue_Hair", "blue hair"),
                matches(SourceKey.PIXIV, "ｍｉｋｕ", "miku"),
                matches(SourceKey.GELBOORU, "-cat", "cat"),
            ),
        )
    }
}
