package com.theoriacodex.app.triage

import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.SearchAnimationFilterRestoreState
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriageVisibilityTest {
    private val fab = FeedFabRestoreState(
        animatedOnly = true,
        hideLiked = true,
        hideWatched = true,
        searchAnimationByScope = mapOf(
            SourceKey.PIXIV.id to SearchAnimationFilterRestoreState(animatedOnly = true, durationMinBucket = 3),
            SourceKey.NHENTAI.id to SearchAnimationFilterRestoreState(animatedOnly = true, durationMinBucket = 3),
        ),
    )

    @Test fun `Search reads the animation filter of the applied query's source and the hide toggles`() {
        val filters = gridVisibilityFilters(ViewerStreamSource.SEARCH, fab, Query.newest(SourceKey.PIXIV))
        assertTrue(filters.animatedOnly && filters.hideLiked && filters.hideWatched)
        assertEquals(AnimatedDurationRange(minBucket = 3), filters.animatedDurationRange)
    }

    @Test fun `nhentai galleries are never animation filtered`() {
        val filters = gridVisibilityFilters(ViewerStreamSource.SEARCH, fab, Query.newest(SourceKey.NHENTAI))
        assertFalse(filters.animatedOnly)
        assertEquals(AnimatedDurationRange.Full, filters.animatedDurationRange)
    }

    @Test fun `For You filters by animation only, as its grid does`() {
        val filters = gridVisibilityFilters(ViewerStreamSource.FOR_YOU, fab, null)
        assertTrue(filters.animatedOnly)
        assertFalse(filters.hideLiked || filters.hideWatched)
    }
}
