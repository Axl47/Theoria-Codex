package com.theoriacodex.app.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPageDemandTest {
    @Test
    fun `hidden first pages drain only three pages before an explicit continue`() {
        val demand = FeedPageDemand()
        for (generation in 1L..3L) {
            assertTrue(demand.update(hiddenPage.copy(completedGeneration = generation)).loadNextPage)
        }
        val paused = hiddenPage.copy(completedGeneration = 4L)
        assertEquals(FeedPageDemandResult(showContinue = true), demand.update(paused))
        assertEquals(FeedPageDemandResult(showContinue = true), demand.update(paused))

        demand.continueLoading()

        assertTrue(demand.update(paused).loadNextPage)
        assertFalse(demand.update(paused).loadNextPage)
    }

    @Test
    fun `repeated viewport callbacks cannot submit duplicate page requests`() {
        val demand = FeedPageDemand()
        assertTrue(demand.update(hiddenPage).loadNextPage)
        repeat(10) {
            assertEquals(FeedPageDemandResult(), demand.update(hiddenPage.copy(atVisibleEnd = it % 2 == 0)))
        }
        assertFalse(demand.update(hiddenPage.copy(paging = true, completedGeneration = 2L)).loadNextPage)
        assertTrue(demand.update(hiddenPage.copy(completedGeneration = 2L)).loadNextPage)
    }

    @Test
    fun `empty provider pages use settled generations even with no observed loading frame`() {
        val demand = FeedPageDemand()
        val empty = hiddenPage.copy(canonicalCount = 0, hasLocalFilters = false)
        assertTrue(demand.update(empty).loadNextPage)
        assertTrue(demand.update(empty.copy(completedGeneration = 2L)).loadNextPage)
        assertTrue(demand.update(empty.copy(completedGeneration = 3L)).loadNextPage)
        assertTrue(demand.update(empty.copy(completedGeneration = 4L)).showContinue)
        assertEquals(
            FeedPageDemandResult(),
            demand.update(empty.copy(completedGeneration = 4L, canLoadMore = false)),
        )
    }

    @Test
    fun `filtered tail remains pageable when visible posts precede the canonical threshold`() {
        val demand = FeedPageDemand(automaticPageLimit = 1)
        val sparseTail = hiddenPage.copy(visibleCount = 20, lastVisibleCanonicalIndex = 25, atVisibleEnd = true)
        assertTrue(demand.update(sparseTail).loadNextPage)
        assertTrue(demand.update(sparseTail.copy(completedGeneration = 2L)).showContinue)
    }

    @Test
    fun `filters that fill the viewport do not drain beyond demand`() {
        val demand = FeedPageDemand()
        assertEquals(
            FeedPageDemandResult(),
            demand.update(hiddenPage.copy(visibleCount = 30, lastVisibleCanonicalIndex = 10)),
        )
    }

    @Test
    fun `pending duration resolution and active loading consume no budget`() {
        val demand = FeedPageDemand(automaticPageLimit = 1)
        assertFalse(demand.update(hiddenPage.copy(ready = false)).loadNextPage)
        assertFalse(demand.update(hiddenPage.copy(refreshing = true)).loadNextPage)
        assertFalse(demand.update(hiddenPage.copy(paging = true)).loadNextPage)
        assertFalse(demand.update(hiddenPage.copy(resolvingDurations = true)).loadNextPage)
        assertTrue(demand.update(hiddenPage).loadNextPage)
        assertFalse(demand.update(hiddenPage.copy(completedGeneration = 2L, resolvingDurations = true)).showContinue)
        assertTrue(demand.update(hiddenPage.copy(completedGeneration = 2L)).showContinue)
    }

    @Test
    fun `failed page does not retry when viewport changes or the error clears`() {
        val demand = FeedPageDemand()
        assertTrue(demand.update(hiddenPage).loadNextPage)
        assertFalse(demand.update(hiddenPage.copy(failed = true)).loadNextPage)
        assertFalse(demand.update(hiddenPage.copy(completedGeneration = 2L, scrolling = true)).loadNextPage)
        assertTrue(demand.update(hiddenPage.copy(contextKey = "new-query", completedGeneration = 3L)).loadNextPage)
    }

    @Test
    fun `query replacement resets exhausted budget and pending demand`() {
        val demand = FeedPageDemand(automaticPageLimit = 1)
        assertTrue(demand.update(hiddenPage).loadNextPage)
        assertTrue(demand.update(hiddenPage.copy(completedGeneration = 2L)).showContinue)
        assertTrue(demand.update(hiddenPage.copy(contextKey = "replacement")).loadNextPage)
        assertFalse(demand.update(hiddenPage.copy(contextKey = "replacement")).loadNextPage)
    }

    @Test
    fun `refresh of the same query resets its exhausted budget`() {
        val demand = FeedPageDemand(automaticPageLimit = 1)
        assertTrue(demand.update(hiddenPage).loadNextPage)
        assertTrue(demand.update(hiddenPage.copy(completedGeneration = 2L)).showContinue)
        assertFalse(demand.update(hiddenPage.copy(refreshing = true)).loadNextPage)
        assertTrue(demand.update(hiddenPage.copy(completedGeneration = 3L)).loadNextPage)
    }

    @Test
    fun `new scroll demand resets budget while layout only changes do not`() {
        val demand = FeedPageDemand(automaticPageLimit = 1)
        val tail = hiddenPage.copy(visibleCount = 100, hasLocalFilters = false, lastVisibleCanonicalIndex = 80)
        assertTrue(demand.update(tail).loadNextPage)
        val settled = tail.copy(completedGeneration = 2L)
        assertTrue(demand.update(settled.copy(lastVisibleCanonicalIndex = 81)).showContinue)
        assertTrue(demand.update(settled.copy(lastVisibleCanonicalIndex = 81, scrolling = true)).loadNextPage)
        assertFalse(demand.update(settled.copy(lastVisibleCanonicalIndex = 81, scrolling = true)).loadNextPage)
    }
}

private val hiddenPage = FeedPageDemandInput(
    contextKey = "query",
    completedGeneration = 1L,
    canonicalCount = 100,
    visibleCount = 0,
    canLoadMore = true,
    hasLocalFilters = true,
)
