package com.theoriacodex.app.series

import com.theoriacodex.data.repository.CheckProgress
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    fun aRefreshShowsHowManySeriesAreCheckedAsEachFinishesThenWhatWasNew_adr_0219() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val step = Channel<Unit>()
        val update = SeriesUpdate(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/a"), "A"), listOf(SeriesEntry("/c/2", "Chapter 2")))
        val viewModel = SeriesTabViewModel(NothingStored, { emptyList() }) { onProgress ->
            (0..2).forEach { done ->
                step.receive()
                onProgress(CheckProgress(done, 2))
            }
            step.receive()
            FollowedCheck(listOf(update), checked = 2, failed = 0)
        }
        viewModel.refresh()
        runCurrent()
        assertEquals(CheckProgress(0, 0), viewModel.state.value.checking)
        (0..2).forEach { done ->
            step.send(Unit)
            runCurrent()
            assertEquals(CheckProgress(done, 2), viewModel.state.value.checking)
        }
        step.send(Unit)
        advanceTimeBy(1.seconds)
        assertNull(viewModel.state.value.checking)
        assertEquals(listOf(update), viewModel.state.value.found)
        advanceUntilIdle()
        assertEquals(emptyList<SeriesUpdate>(), viewModel.state.value.found)
    }

    @Test
    fun theFailureSaysHowManySeriesFailedUnlessAllDid() {
        val failed = FollowedCheck(emptyList(), checked = 2, failed = 2)
        assertEquals("Couldn't check for updates", failed.message)
        assertEquals("Couldn't check 1 series", failed.copy(failed = 1).message)
    }
}
