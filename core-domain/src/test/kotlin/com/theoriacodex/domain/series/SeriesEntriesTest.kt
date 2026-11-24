package com.theoriacodex.domain.series

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesEntriesTest {
    @Test
    fun `a listing without a series memo keeps the stored one and an empty one clears it (ADR 0007)`() {
        val stored = Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/s"), "Series", memo = """{"slug":"s-1"}""")

        assertEquals("""{"slug":"s-1"}""", SeriesEntries(emptyList()).memoOn(stored).memo)
        assertEquals("{}", SeriesEntries(emptyList(), seriesMemo = "{}").memoOn(stored).memo)
    }
}
