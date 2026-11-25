package com.theoriacodex.app.viewer

import com.theoriacodex.app.ui.navigation.TrailFork
import com.theoriacodex.app.ui.navigation.TrailOutcome
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerTrailForksTest {
    private val art = post("art").id
    private val other = post("other").id
    private val dog = TrailFork.Tag("dog")

    @Test
    fun `one fork loads at a time, and every entry point sees it`() = runTest {
        val forks = ViewerTrailForks(backgroundScope)
        val first = CompletableDeferred<TrailOutcome>()

        assertTrue(forks.open(dog, art) { first.await() })
        runCurrent()
        assertFalse(forks.open(TrailFork.Similar, art) { TrailOutcome.Opened })
        assertEquals(dog, forks.busy)

        first.complete(TrailOutcome.Opened)
        runCurrent()
        assertNull(forks.busy)
        assertNull(forks.failed)
    }

    @Test
    fun `a fork that opens nothing says so on its chip for a moment, telling empty from failed`() = runTest {
        val forks = ViewerTrailForks(backgroundScope)

        forks.open(dog, art) { TrailOutcome.Empty }
        runCurrent()
        assertEquals(dog, forks.failed)
        assertTrue(forks.failedEmpty)
        advanceTimeBy(3_000)

        forks.open(dog, art) { TrailOutcome.Failed }
        runCurrent()
        assertEquals(dog, forks.failed)
        assertFalse(forks.failedEmpty)

        advanceTimeBy(3_000)
        assertNull(forks.failed)
    }

    @Test
    fun `paging to another post cancels the fork, and a late finish leaves the next fork alone`() = runTest {
        val forks = ViewerTrailForks(backgroundScope)
        var cancelled = false
        forks.open(dog, art) {
            try {
                CompletableDeferred<TrailOutcome>().await()
            } finally {
                cancelled = true
            }
        }
        runCurrent()

        forks.follow(art)
        assertEquals(dog, forks.busy)
        forks.follow(other)
        assertNull(forks.busy)
        assertTrue(forks.open(TrailFork.Similar, other) { CompletableDeferred<TrailOutcome>().await() })
        advanceTimeBy(1)
        runCurrent()

        assertTrue(cancelled)
        assertEquals(TrailFork.Similar, forks.busy)
    }

    @Test
    fun `a swipe heading to another post cancels the fork before the page lands`() = runTest {
        val forks = ViewerTrailForks(backgroundScope)
        var opened = false
        val search = CompletableDeferred<TrailOutcome>()
        forks.open(dog, art) { search.await().also { opened = true } }
        runCurrent()

        forks.leave()
        search.complete(TrailOutcome.Opened)
        runCurrent()

        assertFalse(opened)
        assertNull(forks.busy)
        assertTrue(forks.open(dog, art) { TrailOutcome.Opened })
    }
}
