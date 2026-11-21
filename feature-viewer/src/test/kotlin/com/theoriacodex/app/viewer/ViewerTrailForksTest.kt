package com.theoriacodex.app.viewer

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
    private val dog = ViewerTrailChip.Tag("dog")

    @Test
    fun `one fork loads at a time, and every entry point sees it`() = runTest {
        val forks = ViewerTrailForks(backgroundScope)
        val first = CompletableDeferred<Boolean>()

        assertTrue(forks.open(dog, art) { first.await() })
        runCurrent()
        assertFalse(forks.open(ViewerTrailChip.Similar, art) { true })
        assertEquals(dog, forks.busy)

        first.complete(true)
        runCurrent()
        assertNull(forks.busy)
        assertNull(forks.failed)
    }

    @Test
    fun `a fork that opens nothing says so on its chip for a moment`() = runTest {
        val forks = ViewerTrailForks(backgroundScope)

        forks.open(dog, art) { false }
        runCurrent()
        assertEquals(dog, forks.failed)

        advanceTimeBy(3_000)
        assertNull(forks.failed)
    }

    @Test
    fun `paging to another post cancels the fork, and a late finish leaves the next fork alone`() = runTest {
        val forks = ViewerTrailForks(backgroundScope)
        var cancelled = false
        forks.open(dog, art) {
            try {
                CompletableDeferred<Boolean>().await()
            } finally {
                cancelled = true
            }
        }
        runCurrent()

        forks.follow(art)
        assertEquals(dog, forks.busy)
        forks.follow(other)
        assertNull(forks.busy)
        assertTrue(forks.open(ViewerTrailChip.Similar, other) { CompletableDeferred<Boolean>().await() })
        advanceTimeBy(1)
        runCurrent()

        assertTrue(cancelled)
        assertEquals(ViewerTrailChip.Similar, forks.busy)
    }
}
