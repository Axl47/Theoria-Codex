package com.theoriacodex.app.series

import com.theoriacodex.data.repository.FollowedCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A check of the followed series that found nothing and failed nowhere. */
internal val NOTHING_CHECKED = FollowedCheck(emptyList(), checked = 0, failed = 0)

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesTabRefreshTest {
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aRefreshWhoseSourcesFailSaysSoUntilTheNextStarts() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val failed = FollowedCheck(emptyList(), checked = 2, failed = 2)
        var next = failed
        val viewModel = SeriesTabViewModel(NothingStored, { emptyList() }) { next }
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(failed, viewModel.state.value.failedCheck)

        next = NOTHING_CHECKED
        viewModel.refresh()
        advanceUntilIdle()
        assertNull(viewModel.state.value.failedCheck)
    }

    @Test
    fun theFailureSaysHowManySeriesFailedUnlessAllDid() {
        val failed = FollowedCheck(emptyList(), checked = 2, failed = 2)
        assertEquals("Couldn't check for updates", failed.message)
        assertEquals("Couldn't check 1 series", failed.copy(failed = 1).message)
    }
}
