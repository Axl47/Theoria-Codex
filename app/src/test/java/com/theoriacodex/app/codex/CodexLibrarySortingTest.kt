package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.data.repository.CodexStatistics
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.model.Codex
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodexLibrarySortingTest {
    private val likes = Codex("likes", "Likes", 0)
    private val manual = listOf(
        Codex("b", "beta", 30), likes, Codex("a", "Alpha", 10), Codex("c", "Gamma", 20), Codex("t", "gamma", 20),
    )
    private val counts = mapOf("a" to 5, "b" to 1, "t" to 5, "likes" to 99)

    private fun sorted(sort: CodexLibrarySort) =
        sortCodicesForLibrary(manual, sort, counts, "likes").map(Codex::codexId)

    @Test fun `manual keeps the stored order including the Likes slot`() {
        assertEquals(listOf("b", "likes", "a", "c", "t"), sorted(CodexLibrarySort.MANUAL))
    }

    @Test fun `automatic sorts pin Likes and keep manual order on ties`() {
        assertEquals(listOf("likes", "a", "b", "c", "t"), sorted(CodexLibrarySort.NAME_ASCENDING))
        assertEquals(listOf("likes", "c", "t", "b", "a"), sorted(CodexLibrarySort.NAME_DESCENDING))
        assertEquals(listOf("likes", "b", "c", "t", "a"), sorted(CodexLibrarySort.NEWEST))
        assertEquals(listOf("likes", "a", "c", "t", "b"), sorted(CodexLibrarySort.OLDEST))
        assertEquals(listOf("likes", "a", "t", "b", "c"), sorted(CodexLibrarySort.MOST_ITEMS))
        assertEquals(listOf("likes", "c", "b", "a", "t"), sorted(CodexLibrarySort.FEWEST_ITEMS))
    }

    @Test fun `usage sorts pin Likes, break ties on the other figure, then keep manual order`() {
        val usage = mapOf(
            "a" to CodexLibraryUsage(watchingMs = 100L, opens = 1L),
            "c" to CodexLibraryUsage(watchingMs = 100L, opens = 7L),
            "t" to CodexLibraryUsage(watchingMs = 500L, opens = 1L),
            "likes" to CodexLibraryUsage(watchingMs = 0L, opens = 0L),
        )
        fun sortedBy(sort: CodexLibrarySort) =
            sortCodicesForLibrary(manual, sort, counts, "likes", usage).map(Codex::codexId)
        assertEquals(listOf("likes", "t", "c", "a", "b"), sortedBy(CodexLibrarySort.MOST_WATCHED))
        assertEquals(listOf("likes", "c", "t", "a", "b"), sortedBy(CodexLibrarySort.MOST_OPENED))
    }

    @Test fun `usage combines watch time and opens per Codex and only emits when they change`() = runTest {
        val repository = FakeStatisticsRepository()
        val emissions = mutableListOf<Map<String, CodexLibraryUsage>>()
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeCodexLibraryUsage().toList(emissions)
        }
        repository.statistics.value = LifetimeStatistics(
            codexStatistics = mapOf("watched" to CodexStatistics(browsingMs = 9L, watchingMs = 40L)),
            codexEntryCounts = mapOf("watched" to 2L, "opened" to 3L),
        )
        repository.statistics.update { it.copy(appOpenCount = 1L) }
        collecting.cancel()
        assertEquals(
            listOf(
                emptyMap(),
                mapOf("watched" to CodexLibraryUsage(40L, 2L), "opened" to CodexLibraryUsage(0L, 3L)),
            ),
            emissions,
        )
    }

    @Test fun `a statistics failure leaves no usage instead of failing the Library`() = runTest {
        val failing = FakeStatisticsRepository(flow { throw IOException("unreadable") })
        assertEquals(emptyMap<String, CodexLibraryUsage>(), failing.observeCodexLibraryUsage().first())
    }

    private class FakeStatisticsRepository(
        private val source: Flow<LifetimeStatistics>? = null,
    ) : StatisticsRepository by InMemoryStatisticsRepository() {
        val statistics = MutableStateFlow(LifetimeStatistics())
        override fun observeStatistics(): Flow<LifetimeStatistics> = source ?: statistics
    }
}
