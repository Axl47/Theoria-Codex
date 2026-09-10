package com.theoriacodex.app.search

import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAnimationFilterRestoreTest {
    @Test
    fun `animation controls are restored independently for each search source`() {
        val gelbooru = QueryMode.Source(SourceKey.GELBOORU)
        val nhentai = QueryMode.Source(SourceKey.NHENTAI)

        val restored = FeedFabRestoreState(hideLiked = true)
            .withSearchAnimationFilter(gelbooru) { state ->
                state.copy(animatedOnly = true, durationMinBucket = 3, durationMaxBucket = 8)
            }

        assertTrue(restored.searchAnimationFilter(gelbooru).animatedOnly)
        assertEquals(3, restored.searchAnimationFilter(gelbooru).durationMinBucket)
        assertFalse(restored.searchAnimationFilter(nhentai).animatedOnly)
        assertEquals(0, restored.searchAnimationFilter(nhentai).durationMinBucket)
        assertTrue(restored.hideLiked)
    }

    @Test
    fun `unified search does not inherit a single source animation filter`() {
        val gelbooru = QueryMode.Source(SourceKey.GELBOORU)
        val restored = FeedFabRestoreState().withSearchAnimationFilter(gelbooru) { state ->
            state.copy(animatedOnly = true)
        }

        assertFalse(restored.searchAnimationFilter(QueryMode.Unified).animatedOnly)
    }
}
