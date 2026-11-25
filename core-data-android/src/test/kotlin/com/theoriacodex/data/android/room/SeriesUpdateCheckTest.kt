package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.CheckFailure
import com.theoriacodex.data.repository.CheckProgress
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.data.repository.checkFollowed
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.model.SourceKey
import java.net.SocketTimeoutException
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntries
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.theoriacodex.data.repository.refresh
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
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
    private val repository = RoomSeriesRepository(database) { 100L }
    private val source = FakeSource("custom_mihon_1")
    private val series = Series(SeriesId(source.key, "/series/a"), "Series", coverUrl = "https://cdn.test/a.jpg")
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

        assertEquals(emptyList<SeriesUpdate>(), repository.checkFollowed(sourcesOn(source)).updates)
        assertEquals(2, repository.observeFollowed().first().single().unreadCount)
    }

    @Test
    fun `a chapter at an address not stored before is new, and an unchanged list has none`() = runTest {
        follow(series)
        source.chapters[series.id] = listOf(first)
        repository.checkFollowed(sourcesOn(source))

        source.chapters[series.id] = listOf(second, first.copy(name = "Chapter 1, renamed"))
        assertEquals(listOf(SeriesUpdate(series, listOf(second))), repository.checkFollowed(sourcesOn(source)).updates)
        assertEquals(emptyList<SeriesUpdate>(), repository.checkFollowed(sourcesOn(source)).updates)
    }

    @Test
    fun `a check hands the source the stored chapters and keeps the series memo it returns`() = runTest {
        follow(series)
        source.chapters[series.id] = listOf(second, first.copy(memo = """{"id":1}"""))
        repository.checkFollowed(sourcesOn(source))

        source.listingMemo = """{"pages":3}"""
        repository.checkFollowed(sourcesOn(source))

        assertEquals(listOf(emptyList(), listOf(second, first.copy(memo = """{"id":1}"""))), source.handed)
        assertEquals("""{"pages":3}""", repository.series(series.id)!!.series.memo)

        source.chapters[series.id] = emptyList()
        source.listingMemo = "{}"
        repository.checkFollowed(sourcesOn(source))
        assertEquals("{}", repository.series(series.id)!!.series.memo)
        assertEquals(2, repository.entries(series.id).size)
    }

    @Test
    fun `a failing source and a missing one are skipped while the others are checked`() = runTest {
        val failing = FakeSource("custom_mihon_2", fails = true)
        val onFailing = Series(SeriesId(failing.key, "/series/b"), "Broken")
        val uninstalled = Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_3"), "/series/c"), "Gone")
        listOf(series, onFailing, uninstalled).forEach { follow(it) }
        source.chapters[series.id] = listOf(first)
        repository.checkFollowed(sourcesOn(failing, source))

        source.chapters[series.id] = listOf(second, first)
        assertEquals(
            FollowedCheck(listOf(SeriesUpdate(series, listOf(second))), checked = 2, failures = listOf(CheckFailure(onFailing, SourceFailureReason.UNKNOWN))),
            repository.checkFollowed(sourcesOn(failing, source)),
        )
    }

    @Test
    fun `progress counts each due series as it finishes, failures included and missing extensions left out (ADR 0218)`() = runTest {
        val failing = FakeSource("custom_mihon_2", fails = true)
        val onFailing = Series(SeriesId(failing.key, "/series/b"), "Broken")
        val uninstalled = Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_3"), "/series/c"), "Gone")
        listOf(series, onFailing, uninstalled).forEach { follow(it) }
        val progress = mutableListOf<CheckProgress>()

        repository.checkFollowed(sourcesOn(failing, source), onProgress = { progress += it })

        assertEquals(CheckProgress(0, 3), progress.first())
        assertEquals(CheckProgress(2, 2), progress.last())
        assertEquals(listOf(0, 1, 2), progress.map(CheckProgress::done).distinct())
    }

    @Test
    fun `a failed series keeps why it failed, and a retry checks only the series it names (ADR 0219)`() = runTest {
        val blocked = FakeSource("custom_mihon_2", fails = true, failure = SourceAdapterException(SourceFailureReason.BLOCKED))
        val slow = FakeSource("custom_mihon_3", fails = true, failure = SocketTimeoutException("slow"))
        val onBlocked = Series(SeriesId(blocked.key, "/series/b"), "Walled")
        val onSlow = Series(SeriesId(slow.key, "/series/c"), "Slow")
        listOf(series, onBlocked, onSlow).forEach { follow(it) }

        val check = repository.checkFollowed(sourcesOn(source, blocked, slow))
        assertEquals(
            setOf(CheckFailure(onBlocked, SourceFailureReason.BLOCKED), CheckFailure(onSlow, SourceFailureReason.TIMED_OUT)),
            check.failures.toSet(),
        )

        val retry = repository.checkFollowed(sourcesOn(source, blocked, slow), only = setOf(onSlow.id))
        assertEquals(1, retry.checked)
        assertEquals(listOf(CheckFailure(onSlow, SourceFailureReason.TIMED_OUT)), retry.failures)
    }

    @Test
    fun `a series is reported once it is stored, even when a later series stops the check`() = runTest {
        val later = Series(SeriesId(source.key, "/series/b"), "Zeta")
        listOf(series, later).forEach { follow(it) }
        source.chapters[series.id] = listOf(first)
        repository.checkFollowed(sourcesOn(source))

        source.chapters[series.id] = listOf(second, first)
        source.stopsAt = later.id
        val reported = mutableListOf<SeriesUpdate>()
        runCatching { repository.checkFollowed(sourcesOn(source)) { reported += it } }

        assertEquals(listOf(SeriesUpdate(series, listOf(second))), reported)
    }

    @Test
    fun `a source that goes away during a check is not asked for its later series`() = runTest {
        val later = Series(SeriesId(source.key, "/series/b"), "Zeta")
        listOf(series, later).forEach { follow(it) }
        source.chapters[series.id] = listOf(first)
        source.chapters[later.id] = listOf(first)
        var asked = 0

        repository.checkFollowed({ source.takeIf { asked++ == 0 } })

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
        val followed = repository.observeFollowed().first()
        val onScheduler = object : SeriesRepository by repository {
            override fun observeFollowed() = flowOf(followed)
        }
        val check = launch { onScheduler.checkFollowed({ askedWhileWaiting += !pageRefresh.isCompleted; null }) }
        runCurrent()
        pageRefresh.complete(Unit)
        joinAll(refresh, check)

        assertEquals(listOf(false), askedWhileWaiting)
    }

    @Test
    fun `leaving a series' page while its refresh stores new chapters still queues them`() = runTest {
        follow(series)
        source.detailsWait = CompletableDeferred(Unit)
        source.chapters[series.id] = listOf(first)
        repository.refresh(source, series)

        source.chapters[series.id] = listOf(second, first)
        val storing = CompletableDeferred<Unit>()
        val stored = CompletableDeferred<Unit>()
        val slowStore = object : SeriesRepository by repository {
            override suspend fun store(series: Series, entries: List<SeriesEntry>?): List<SeriesEntry> {
                storing.complete(Unit)
                stored.await()
                return repository.store(series, entries)
            }
        }
        val queued = mutableListOf<List<SeriesEntry>>()
        val refresh = launch { slowStore.refresh(source, series) { currentCoroutineContext().ensureActive(); queued += it } }
        storing.await()
        refresh.cancel()
        stored.complete(Unit)
        refresh.join()

        assertEquals(listOf(listOf(second)), queued)
    }

    @Test
    fun `a first refresh and one that finds nothing new queue nothing`() = runTest {
        follow(series)
        source.detailsWait = CompletableDeferred(Unit)
        source.chapters[series.id] = listOf(second, first)
        val queued = mutableListOf<List<SeriesEntry>>()

        repository.refresh(source, series) { queued += it }
        repository.refresh(source, series) { queued += it }

        assertEquals(emptyList<List<SeriesEntry>>(), queued)
        assertEquals(2, repository.entries(series.id).size)
    }

    @Test
    fun `unfollowed series are not checked`() = runTest {
        repository.store(series)
        source.chapters[series.id] = listOf(first)

        repository.checkFollowed(sourcesOn(source))

        assertEquals(emptyList<Any>(), repository.entries(series.id))
    }
}

private class FakeSource(id: String, private val fails: Boolean = false, private val failure: Throwable = IllegalStateException("down")) : SeriesSource {
    val chapters = mutableMapOf<SeriesId, List<SeriesEntry>>()

    var stopsAt: SeriesId? = null
    override val key = SourceKey.parseOrThrow(id)
    override val name = id
    override val language = "en"
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), false)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>) = popular(page)

    var detailsWait: CompletableDeferred<Unit>? = null
    override suspend fun details(series: Series): Series = checkNotNull(detailsWait) { "A check asks for chapters only" }.await().let { series }
    val handed = mutableListOf<List<SeriesEntry>>()
    var listingMemo: String? = null
    override suspend fun entries(series: Series, stored: List<SeriesEntry>) = when {
        fails -> throw failure
        series.id == stopsAt -> throw CancellationException("stopped")
        else -> SeriesEntries(chapters[series.id].orEmpty(), listingMemo).also { handed += stored }
    }
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Pages(emptyList())
}

internal fun sourcesOn(vararg sources: SeriesSource): suspend (SourceKey) -> SeriesSource? = { key -> sources.firstOrNull { it.key == key } }
