package com.theoriacodex.app.series

import com.theoriacodex.data.repository.CheckFailure
import com.theoriacodex.data.repository.CheckProgress
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.domain.series.SeriesKind
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
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

internal fun failure(path: String, source: String = "custom_mihon_1", reason: SourceFailureReason = SourceFailureReason.NETWORK) =
    CheckFailure(Series(SeriesId(SourceKey.parseOrThrow(source), path), path), reason)

internal val NOTHING_CHECKED = FollowedCheck(emptyList(), checked = 0)

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesTabRefreshTest {
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aRefreshWhoseSourcesFailSaysSoUntilTheNextStarts() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val failed = FollowedCheck(emptyList(), checked = 2, failures = List(2) { failure("/f$it") })
        var next = failed
        val viewModel = SeriesTabViewModel(NothingStored, { emptyList() }) { _, _ -> next }
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(failed, viewModel.state.value.failedCheck)

        next = NOTHING_CHECKED
        viewModel.refresh()
        advanceUntilIdle()
        assertNull(viewModel.state.value.failedCheck)
    }

    @Test
    fun aRefreshShowsHowManySeriesAreCheckedAsEachFinishesThenItsResult_adr_0219() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val step = Channel<Unit>()
        val viewModel = SeriesTabViewModel(NothingStored, { emptyList() }) { _, onProgress ->
            (0..2).forEach { done ->
                step.receive()
                onProgress(CheckProgress(done, 2))
            }
            step.receive()
            FollowedCheck(emptyList(), checked = 2)
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
        assertEquals(FollowedCheck(emptyList(), checked = 2), viewModel.state.value.finished)
        advanceUntilIdle()
        assertNull(viewModel.state.value.finished)
    }

    @Test
    fun dismissingTheFailureHidesItUntilAnotherCheckFails_adr_0220() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val failed = FollowedCheck(emptyList(), checked = 3, failures = listOf(failure("/a")))
        val viewModel = SeriesTabViewModel(NothingStored, { emptyList() }) { _, _ -> failed }
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.dismissFailures()
        assertNull(viewModel.state.value.failedCheck)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(failed, viewModel.state.value.failedCheck)
    }

    @Test
    fun retryingAGroupChecksOnlyItsSeriesAndKeepsTheOtherFailures_adr_0220() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reachable = failure("/a")
        val limited = failure("/b", reason = SourceFailureReason.RATE_LIMITED)
        val asked = mutableListOf<Set<SeriesId>?>()
        val viewModel = SeriesTabViewModel(NothingStored, { emptyList() }) { only, _ ->
            asked += only
            if (only == null) FollowedCheck(emptyList(), checked = 3, failures = listOf(reachable, limited)) else FollowedCheck(emptyList(), checked = 1)
        }
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.checkRun)

        viewModel.retry(setOf(limited.series.id))
        advanceUntilIdle()

        assertEquals(listOf(null, setOf(limited.series.id)), asked)
        assertEquals(listOf(reachable), viewModel.state.value.failedCheck?.failures)
        assertEquals(1, viewModel.state.value.checkRun)
    }

    @Test
    fun dismissingDuringAGroupRetryKeepsOnlyWhatTheRetryFound_adr_0220() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reachable = failure("/a")
        val limited = failure("/b", reason = SourceFailureReason.RATE_LIMITED)
        val retrying = CompletableDeferred<FollowedCheck>()
        val viewModel = SeriesTabViewModel(NothingStored, { emptyList() }) { only, _ ->
            if (only == null) FollowedCheck(emptyList(), checked = 3, failures = listOf(reachable, limited)) else retrying.await()
        }
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.retry(setOf(limited.series.id))
        runCurrent()

        viewModel.dismissFailures()
        retrying.complete(FollowedCheck(emptyList(), checked = 1))
        advanceUntilIdle()

        assertNull(viewModel.state.value.failedCheck)
    }

    @Test
    fun aRefreshChecksOnlyTheFilteredKind_adr_0219() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manga = failure("/m")
        val anime = failure("/a", source = "custom_aniyomi_1")
        val asked = mutableListOf<Set<SeriesId>?>()
        val followed = Following(flowOf(listOf(manga, anime).map { FollowedSeries(it.series, unreadCount = 0) }))
        val viewModel = SeriesTabViewModel(followed, { emptyList() }) { only, _ ->
            asked += only
            val scoped = listOf(manga, anime).filter { only == null || it.series.id in only }
            FollowedCheck(emptyList(), checked = scoped.size, failures = scoped)
        }
        backgroundScope.launch { viewModel.content.collect {} }
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.chooseKind(SeriesKind.ANIME)
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf(null, setOf(anime.series.id)), asked)
        assertEquals(FollowedCheck(emptyList(), checked = 1, failures = listOf(anime)), viewModel.state.value.failedCheck)
    }

    @Test
    fun theFailureSaysHowManySeriesFailedUnlessAllDid() {
        val failed = FollowedCheck(emptyList(), checked = 2, failures = List(2) { failure("/f$it") })
        assertEquals("Couldn't check for updates", failed.message)
        assertEquals("Couldn't check 1 series", failed.copy(failures = failed.failures.take(1)).message)
    }
}
