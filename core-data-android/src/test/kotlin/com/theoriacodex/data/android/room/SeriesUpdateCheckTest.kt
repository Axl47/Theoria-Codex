package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.data.repository.checkFollowed
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.theoriacodex.data.repository.refresh
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SeriesUpdateCheckTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TheoriaRoomDatabase::class.java)
        .allowMainThreadQueries().build()
    private var now = 100L
    private val repository = RoomSeriesRepository(database) { now }
    private val source = FakeSource("custom_mihon_1")
    private val series = Series(SeriesId(source.key, "/series/a"), "Series")
    private val first = SeriesEntry("/c/1", "Chapter 1")
    private val second = SeriesEntry("/c/2", "Chapter 2")

    @After fun close() = database.close()

    private suspend fun follow(series: Series) {
        repository.store(series)
        repository.setFollowed(series.id, true)
    }

    @Test
    fun `a series' first check stores its chapters and counts none as new`() = runTest {
        follow(series)
        source.chapters[series.id] = listOf(second, first)

        assertEquals(emptyList<SeriesUpdate>(), repository.checkFollowed({ listOf(source) }))
        assertEquals(2, repository.observeFollowed().first().single().unreadCount)
        assertEquals(100L, repository.series(series.id)!!.lastCheckedAtEpochMs)
    }

    @Test
    fun `a chapter at an address not stored before is new, and an unchanged list has none`() = runTest {
        follow(series)
        source.chapters[series.id] = listOf(first)
        repository.checkFollowed({ listOf(source) })

        now = 200L
        source.chapters[series.id] = listOf(second, first.copy(name = "Chapter 1, renamed"))
        assertEquals(listOf(SeriesUpdate(series, listOf(second))), repository.checkFollowed({ listOf(source) }))
        assertEquals(200L, repository.series(series.id)!!.lastCheckedAtEpochMs)

        assertEquals(emptyList<SeriesUpdate>(), repository.checkFollowed({ listOf(source) }))
    }

    @Test
    fun `a failing source and a missing one are skipped while the others are checked`() = runTest {
        val failing = FakeSource("custom_mihon_2", fails = true)
        val onFailing = Series(SeriesId(failing.key, "/series/b"), "Broken")
        val uninstalled = Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_3"), "/series/c"), "Gone")
        listOf(series, onFailing, uninstalled).forEach { follow(it) }
        source.chapters[series.id] = listOf(first)
        repository.checkFollowed({ listOf(failing, source) })

        source.chapters[series.id] = listOf(second, first)
        assertEquals(listOf(SeriesUpdate(series, listOf(second))), repository.checkFollowed({ listOf(failing, source) }))
    }

    @Test
    fun `a series is reported once it is stored, even when a later series stops the check`() = runTest {
        val later = Series(SeriesId(source.key, "/series/b"), "Zeta")
        listOf(series, later).forEach { follow(it) }
        source.chapters[series.id] = listOf(first)
        repository.checkFollowed({ listOf(source) })

        source.chapters[series.id] = listOf(second, first)
        source.stopsAt = later.id
        val reported = mutableListOf<SeriesUpdate>()
        runCatching { repository.checkFollowed({ listOf(source) }) { reported += it } }

        assertEquals(listOf(SeriesUpdate(series, listOf(second))), reported)
    }

    @Test
    fun `a source that goes away during a check is not asked for its later series`() = runTest {
        val later = Series(SeriesId(source.key, "/series/b"), "Zeta")
        listOf(series, later).forEach { follow(it) }
        source.chapters[series.id] = listOf(first)
        source.chapters[later.id] = listOf(first)
        var asked = 0

        // The source is on for the first series and gone by the second.
        repository.checkFollowed({ if (asked++ == 0) listOf(source) else emptyList() })

        assertEquals(1, repository.entries(series.id).size)
        assertEquals(emptyList<Any>(), repository.entries(later.id))
    }

    @Test
    fun `a check that waited for a series' page to refresh asks which sources are on only afterwards`() = runTest {
        follow(series)
        source.chapters[series.id] = listOf(first)
        val pageRefresh = CompletableDeferred<Unit>()
        source.detailsWait = pageRefresh
        val refresh = launch { repository.refresh(source, series) }
        runCurrent()

        val askedWhileWaiting = mutableListOf<Boolean>()
        val check = launch { repository.checkFollowed({ emptyList<SeriesSource>().also { askedWhileWaiting += !pageRefresh.isCompleted } }) }
        // Real time for the check to read the followed series and reach the refresh it has to wait for.
        withContext(Dispatchers.Default) { delay(200) }
        pageRefresh.complete(Unit)
        joinAll(refresh, check)

        assertEquals(listOf(false), askedWhileWaiting)
    }

    @Test
    fun `unfollowed series are not checked`() = runTest {
        repository.store(series)
        source.chapters[series.id] = listOf(first)

        repository.checkFollowed({ listOf(source) })

        assertEquals(emptyList<Any>(), repository.entries(series.id))
    }
}

private class FakeSource(id: String, private val fails: Boolean = false) : SeriesSource {
    val chapters = mutableMapOf<SeriesId, List<SeriesEntry>>()

    /** The series at which the check is cancelled, as the system stopping the job would. */
    var stopsAt: SeriesId? = null
    override val key = SourceKey.parseOrThrow(id)
    override val name = id
    override val language = "en"
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), false)
    override suspend fun search(text: String, page: Int) = popular(page)

    /** What a page's refresh waits for in [details]; a check asks for chapters only. */
    var detailsWait: CompletableDeferred<Unit>? = null
    override suspend fun details(series: Series): Series = checkNotNull(detailsWait) { "A check asks for chapters only" }.await().let { series }
    override suspend fun entries(series: Series) = when {
        fails -> throw IllegalStateException("down")
        series.id == stopsAt -> throw CancellationException("stopped")
        else -> chapters[series.id].orEmpty()
    }
    override suspend fun pages(entry: SeriesEntry) = emptyList<ImageRef>()
}
