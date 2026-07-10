package com.theoriacodex.domain.query

import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCapabilityGateTest {
    @Test
    fun `returns all relevant exclusion reasons per source`() {
        val query = Query(
            mode = QueryMode.Unified,
            includeTerms = listOf(SearchTerm("landscape")),
            excludeTerms = listOf(SearchTerm("comic")),
            sort = SortMode.TOP,
            dateRange = DateRange(fromEpochMs = 1L, toEpochMs = 2L),
            minScore = 100,
        )
        val capabilities = mapOf(
            SourceKey.PIXIV to SourceCapabilities(
                supportsSortNewest = true,
                supportsSortPopular = true,
                supportsSortTop = false,
                supportsSortRandom = true,
                supportsExcludeTagsServerSide = false,
                supportsDateRangeServerSide = false,
                supportsMinScoreServerSide = false,
                requiresCredentials = false,
            ),
            SourceKey.GELBOORU to SourceCapabilities(
                supportsSortNewest = true,
                supportsSortPopular = true,
                supportsSortTop = true,
                supportsSortRandom = true,
                supportsExcludeTagsServerSide = true,
                supportsDateRangeServerSide = true,
                supportsMinScoreServerSide = true,
                requiresCredentials = false,
            ),
        )

        val excluded = SourceCapabilityGate.excludedSources(query, capabilities)

        assertEquals(
            setOf(
                CapabilityExclusionReason.SORT_UNSUPPORTED,
                CapabilityExclusionReason.EXCLUDE_TAGS_UNSUPPORTED,
                CapabilityExclusionReason.DATE_RANGE_UNSUPPORTED,
                CapabilityExclusionReason.MIN_SCORE_UNSUPPORTED,
            ),
            excluded[SourceKey.PIXIV],
        )
        assertTrue(SourceKey.GELBOORU !in excluded)
    }
}
