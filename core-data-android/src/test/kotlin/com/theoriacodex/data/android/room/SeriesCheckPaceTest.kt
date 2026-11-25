package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.SERIES_CHECK_CEILING
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.checkFollowed
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntries
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeriesCheckPaceTest {
    private var now = START
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TheoriaRoomDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repository = RoomSeriesRepository(database) { now }

    @After fun close() = database.close()

    private suspend fun follow(series: Series) {
        repository.store(series)
        repository.setFollowed(series.id, true)
    }

    private suspend fun backgroundRun(vararg sources: SeriesSource) = repository.checkFollowed(sourcesOn(*sources), RUN_EVERY, now)

    @Test
    fun `a background run skips a quiet series it checked lately, and a refresh by hand checks it anyway (ADR 0015)`() = runTest {
        val source = PacedSource("custom_mihon_1") { now }
        val quiet = source.add(Pace.quiet(lastDaysAgo = 300), "quiet")
        follow(quiet)

        assertEquals(1, backgroundRun(source).checked)
        val checkedAt = repository.series(quiet.id)?.checkedAtEpochMs
        now += RUN_EVERY.inWholeMilliseconds
        assertEquals(0 to 1, backgroundRun(source).let { it.checked to it.notDue })
        repository.setFollowed(quiet.id, false)
        repository.setFollowed(quiet.id, true)
        assertEquals(0, backgroundRun(source).checked)
        assertEquals(checkedAt, repository.series(quiet.id)?.checkedAtEpochMs)

        assertEquals(1, repository.checkFollowed(sourcesOn(source), now = now).checked)
        assertEquals(2, source.requests)
    }

    @Test
    fun `a series never checked is found due without loading its chapters (ADR 0015)`() = runTest {
        val loads = AtomicInteger()
        val counting = object : SeriesRepository by repository {
            override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> = repository.entries(id).also { loads.incrementAndGet() }
        }
        follow(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/never"), "Never"))

        counting.checkFollowed({ null }, RUN_EVERY, now)

        assertEquals(0, loads.get())
    }

    @Test
    fun `a listing the series page stores counts as a check, a series stored without its chapters does not (ADR 0015)`() = runTest {
        val source = PacedSource("custom_mihon_1") { now }
        val quiet = source.add(Pace.quiet(lastDaysAgo = 300), "quiet")
        follow(quiet)
        assertNull(repository.series(quiet.id)?.checkedAtEpochMs)

        repository.store(quiet, source.entries(quiet, emptyList()).entries)

        assertEquals(now, repository.series(quiet.id)?.checkedAtEpochMs)
        assertEquals(1, backgroundRun(source).notDue)
    }

    @Test
    fun `a failed or empty listing leaves the series due on the next run (ADR 0015)`() = runTest {
        val source = PacedSource("custom_mihon_1") { now }
        val quiet = source.add(Pace.quiet(lastDaysAgo = 300), "quiet")
        follow(quiet)

        source.down = true
        assertEquals(1, backgroundRun(source).failed)
        source.down = false
        source.empty = true
        backgroundRun(source)
        assertNull(repository.series(quiet.id)?.checkedAtEpochMs)

        source.empty = false
        now += RUN_EVERY.inWholeMilliseconds
        assertEquals(1, backgroundRun(source).checked)
        assertEquals(now, repository.series(quiet.id)?.checkedAtEpochMs)
    }

    @Test
    fun `over four weeks a mixed library needs about half the requests and still hears of every chapter within the ceiling (ADR 0015)`() = runTest {
        val before = simulate(adaptive = false)
        val after = simulate(adaptive = true)
        println("series-check-pace before: $before")
        println("series-check-pace after:  $after")

        assertTrue("$after", after.requests * 5 < before.requests * 3)
        assertEquals(0, after.missed)
        assertTrue("$after", after.slowest <= SERIES_CHECK_CEILING + RUN_EVERY)
        assertEquals(before.slowestActive, after.slowestActive)
        assertEquals(before.activeRequests, after.activeRequests)
    }

    private suspend fun simulate(adaptive: Boolean): Outcome {
        database.clearAllTables()
        now = START
        val random = Random(339)
        val sources = (1..4).map { PacedSource("custom_mihon_$it", oldestFirst = it == 4) { now } }
        val library = LIBRARY.flatMap { (pace, count) -> List(count) { pace(random) } }
        val active = library.mapIndexedNotNull { index, pace ->
            val series = sources[index % sources.size].add(pace, "s$index")
            follow(series)
            series.id.takeIf { pace.active }
        }.toSet()
        repository.checkFollowed(sourcesOn(*sources.toTypedArray()), now = now)
        sources.forEach { it.requests = 0; it.requestsFor.clear() }

        val latencies = mutableListOf<Pair<SeriesId, Long>>()
        val announced = mutableSetOf<String>()
        var runs = 0
        var busiest = 0L
        var busy = 0L
        while (now < START + SIMULATED.inWholeMilliseconds) {
            now += (RUN_EVERY + random.nextLong(-18, 18).minutes).inWholeMilliseconds
            val requestsBefore = sources.map { it.requests }
            repository.checkFollowed(sourcesOn(*sources.toTypedArray()), RUN_EVERY.takeIf { adaptive }, now) { update ->
                update.newEntries.forEach { entry ->
                    announced += entry.address
                    latencies += update.series.id to now - sources.first { it.key == update.series.id.source }.releasedAt(entry.address)
                }
            }
            val took = sources.indices.maxOf { sources[it].requests - requestsBefore[it] } * REQUEST_TIME.inWholeMilliseconds
            busiest = maxOf(busiest, took)
            busy += took
            runs++
        }
        val due = sources.flatMap { it.releasedBetween(START, now - (SERIES_CHECK_CEILING + RUN_EVERY).inWholeMilliseconds) }
        return Outcome(
            series = library.size,
            runs = runs,
            requests = sources.sumOf { it.requests },
            meanRun = (busy / runs).milliseconds,
            longestRun = busiest.milliseconds,
            announced = announced.size,
            missed = due.count { it !in announced },
            slowest = latencies.maxOf { it.second }.milliseconds,
            slowestActive = latencies.filter { it.first in active }.maxOf { it.second }.milliseconds,
            activeRequests = sources.sumOf { source -> active.sumOf { source.requestsFor[it] ?: 0 } },
        )
    }

    private data class Outcome(
        val series: Int,
        val runs: Int,
        val requests: Int,
        val meanRun: Duration,
        val longestRun: Duration,
        val announced: Int,
        val missed: Int,
        val slowest: Duration,
        val slowestActive: Duration,
        val activeRequests: Int,
    ) {
        override fun toString() = "$series series, $runs runs, $requests requests (${"%.1f".format(requests.toFloat() / runs)} per run), " +
            "runs take $meanRun on average and $longestRun at most at $REQUEST_TIME per request, each source's in turn, $announced chapters announced, $missed missed, " +
            "slowest announcement $slowest, slowest for daily and weekly series $slowestActive, $activeRequests requests for them"
    }

    private class Pace(val releases: List<Long>, val dated: Boolean = true, val active: Boolean = false, val datedEarlier: Long = 0) {
        companion object {
            private val day = 1.days.inWholeMilliseconds

            fun every(days: Double, jitterHours: Long, random: Random, dated: Boolean = true, datedEarlier: Long = 0) = Pace(
                generateSequence(START - 60 * day) { it + (days * day).toLong() }.takeWhile { it < START + SIMULATED.inWholeMilliseconds }
                    .map { it + random.nextLong(-jitterHours, jitterHours + 1).hours.inWholeMilliseconds }.toList(),
                dated = dated,
                active = days <= 7.0 && datedEarlier == 0L,
                datedEarlier = datedEarlier,
            )

            fun irregular(random: Random) = Pace(
                generateSequence(START - 90 * day) { it + random.nextLong(3, 41) * day }.takeWhile { it < START + SIMULATED.inWholeMilliseconds }.toList(),
            )

            fun quiet(lastDaysAgo: Long) = Pace(List(30) { START - (lastDaysAgo + 7L * it) * day })

            fun back(random: Random) = Pace(
                List(20) { START - (120L + 7 * it) * day } + List(3) { START + (10L + 7 * it) * day + random.nextLong(0, day) },
            )
        }
    }

    private class PacedSource(id: String, private val oldestFirst: Boolean = false, private val clock: () -> Long) : SeriesSource {
        private val paces = mutableMapOf<SeriesId, Pace>()
        private val released = mutableMapOf<String, Long>()
        var requests = 0
        val requestsFor = mutableMapOf<SeriesId, Int>()
        var down = false
        var empty = false
        override val key = SourceKey.parseOrThrow(id)
        override val name = id
        override val language = "en"

        fun add(pace: Pace, address: String): Series = Series(SeriesId(key, "/$address"), address).also { series ->
            paces[series.id] = pace
            pace.releases.forEachIndexed { index, at -> released["/$address/$index"] = at }
        }

        fun releasedAt(address: String) = released.getValue(address)

        fun releasedBetween(from: Long, until: Long) = released.filterValues { it in (from + 1)..until }.keys

        override suspend fun popular(page: Int) = SeriesPage(emptyList(), false)
        override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>) = popular(page)
        override suspend fun details(series: Series) = series
        override suspend fun media(entry: SeriesEntry) = EntryMedia.Pages(emptyList())

        override suspend fun entries(series: Series, stored: List<SeriesEntry>): SeriesEntries {
            requests++
            requestsFor[series.id] = (requestsFor[series.id] ?: 0) + 1
            check(!down) { "down" }
            if (empty) return SeriesEntries(emptyList())
            val pace = paces.getValue(series.id)
            val listed = released.filter { (address, at) -> address.startsWith("${series.id.address}/") && at <= clock() }
            return SeriesEntries(
                listed.entries.sortedBy { if (oldestFirst) it.value else -it.value }.map { (address, at) ->
                    SeriesEntry(address, address, uploadedAtEpochMs = (at - pace.datedEarlier).takeIf { pace.dated })
                },
            )
        }
    }

    private companion object {
        val START = 20_000.days.inWholeMilliseconds
        val RUN_EVERY = 6.hours
        val SIMULATED = 28.days
        val REQUEST_TIME = 1_000.milliseconds
        val LIBRARY: List<Pair<(Random) -> Pace, Int>> = listOf(
            { random: Random -> Pace.every(1.0, 4, random) } to 4,
            { random: Random -> Pace.every(7.0, 12, random) } to 10,
            { random: Random -> Pace.every(7.0, 12, random, dated = false) } to 3,
            { random: Random -> Pace.every(7.0, 12, random, datedEarlier = 400.days.inWholeMilliseconds) } to 2,
            { random: Random -> Pace.every(30.0, 48, random) } to 6,
            { random: Random -> Pace.irregular(random) } to 3,
            { random: Random -> Pace.quiet(lastDaysAgo = random.nextLong(200, 1000)) } to 20,
            { random: Random -> Pace.back(random) } to 2,
            { _: Random -> Pace(listOf(START - 500.days.inWholeMilliseconds)) } to 2,
        )
    }
}
