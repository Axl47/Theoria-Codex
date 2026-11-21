package com.theoriacodex.app.search

import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAnimationFilterRestoreTest {
    @Test
    fun `duration filters are restored independently for each search scope`() {
        val gelbooru = QueryMode.Source(SourceKey.GELBOORU)

        val restored = FeedFabRestoreState(hideLiked = true)
            .withSearchAnimationFilter(gelbooru) { state -> state.copy(durationMinBucket = 3, durationMaxBucket = 8) }

        assertEquals(3, restored.searchAnimationFilter(gelbooru).durationMinBucket)
        assertEquals(0, restored.searchAnimationFilter(QueryMode.Source(SourceKey.NHENTAI)).durationMinBucket)
        assertEquals(0, restored.searchAnimationFilter(QueryMode.Unified).durationMinBucket)
        assertTrue(restored.hideLiked)
    }
}
