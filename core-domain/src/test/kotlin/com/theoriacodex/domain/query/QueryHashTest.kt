package com.theoriacodex.domain.query

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QueryHashTest {
    @Test
    fun `hash remains stable when include and exclude tags are permuted`() {
        val first = Query(
            mode = QueryMode.Unified,
            includeTags = listOf("landscape", "artist:foo"),
            excludeTags = listOf("lowres", "comic"),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
        val second = Query(
            mode = QueryMode.Unified,
            includeTags = listOf("artist:foo", "landscape"),
            excludeTags = listOf("comic", "lowres"),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        assertEquals(QueryHash.from(first), QueryHash.from(second))
    }

    @Test
    fun `hash changes when sort changes`() {
        val newest = Query(
            mode = QueryMode.Unified,
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
        val popular = newest.copy(sort = SortMode.POPULAR)

        assertNotEquals(QueryHash.from(newest), QueryHash.from(popular))
    }
}
