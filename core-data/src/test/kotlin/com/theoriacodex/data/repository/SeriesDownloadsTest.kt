package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntries
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SeriesDownloadsTest {
    @get:Rule val folder = TemporaryFolder()

    private val source = SourceKey.parseOrThrow("custom_mihon_7")
    private val id = SeriesId(source, "/title/1")

    private val chapters = (3 downTo 1).mapIndexed { order, number -> chapter(number, order) }

    @Test fun `deleting a chapter or a series leaves the rest (ADR 0027)`() = runTest {
        val harness = Harness(this)
        val other = SeriesId(source, "/title/2")
        listOf(id to "/c1", id to "/c2", other to "/c1").forEach { (series, address) -> harness.store.saveChapter(series, address) }

        harness.downloads.delete(id, "/c1")
        assertEquals(setOf("/c2"), harness.downloads.downloaded(id))
        harness.downloads.deleteSeries(id)
        assertEquals(emptySet<String>(), harness.downloads.downloaded(id))
        assertEquals(setOf("/c1"), Harness(this).downloads.downloaded(other))
    }

    @Test fun `the files decide what is downloaded whatever the database or a lost queue says (ADR 0027, 0028)`() = runTest {
        val harness = Harness(this)
        assertEquals(emptyMap<String, ChapterDownload>(), harness.downloads.states(id).first())

        harness.store.saveChapter(id, "/c2")
        assertEquals(mapOf("/c2" to ChapterDownload.OnDevice), Harness(this).downloads.states(id).first())
    }

    @Test fun `the queue downloads in order, one chapter at a time`() = runTest {
        val harness = Harness(this)
        harness.downloads.download(id, listOf("/c1", "/c2", "/c3"))
        assertEquals(listOf("schedule user"), harness.scheduled)

        assertEquals(DrainResult(downloaded = 3), harness.downloads.drain(automatic = false))
        assertEquals(listOf("/c1", "/c1", "/c2", "/c2", "/c3", "/c3"), harness.fetched.map { it.substringBeforeLast('/').removePrefix(PAGES) })
        assertEquals(setOf("/c1", "/c2", "/c3"), harness.downloads.downloaded(id))
        assertEquals(emptyMap<String, ChapterDownload>() + chapters.associate { it.entry.address to ChapterDownload.OnDevice }, harness.downloads.states(id).first())
    }

    @Test fun `a downloaded chapter keeps the cover as an image loader is handed it`() = runTest {
        val harness = Harness(this, stored = StoredSeries(Series(id, "Title", coverUrl = "https://cover.test/1.jpg")))
        harness.downloads.download(id, listOf("/c1"))
        harness.downloads.drain(automatic = false)

        val saved = harness.store.withoutOfflineLocations(checkNotNull(harness.downloads.chapter(id, "/c1")))
        assertEquals("https://cover.test/1.jpg#loadable", saved.preview.url)
    }

    @Test fun `a page whose address names no type is saved under the type its fetch found (ADR 0022)`() = runTest {
        val harness = Harness(this)
        harness.downloads.download(id, listOf("/c1"))
        harness.downloads.drain(automatic = false)

        val pages = checkNotNull(harness.downloads.chapter(id, "/c1")).media
        assertEquals(listOf("image/png", "image/png"), pages.map { it.mime })
        assertTrue(pages.all { checkNotNull(it.localPath).endsWith(".png") })
    }

    @Test fun `a chapter already kept offline, such as in a Codex, is not fetched again (ADR 0027)`() = runTest {
        val harness = Harness(this)
        val pages = twoPages(chapters.last().entry)
        val post = chapterPost(Series(id, "Title"), chapters.last().entry, null, pages)
        harness.store.save(post, pages, "codex:x") { _, file -> file.writeBytes(PAGE); null }
        harness.downloads.download(id, listOf("/c1"))

        assertEquals(DrainResult(downloaded = 1), harness.downloads.drain(automatic = false))
        assertEquals(emptyList<String>(), harness.fetched)
        assertEquals(setOf("/c1"), harness.downloads.downloaded(id))
    }

    @Test fun `cancelling the chapter being fetched stops it, leaves nothing and the queue goes on`() = runTest {
        val harness = Harness(this)
        val never = CompletableDeferred<Unit>()
        harness.onFetch = { url -> if ("/c1/" in url) never.await() }
        harness.downloads.download(id, listOf("/c1", "/c2"))

        val drain = async { harness.downloads.drain(automatic = false) }
        advanceUntilIdle()
        assertEquals(ChapterDownload.Queued(0f), harness.downloads.states(id).first()["/c1"])
        harness.downloads.delete(id, "/c1")

        assertEquals(DrainResult(downloaded = 1), drain.await())
        assertEquals(mapOf("/c2" to ChapterDownload.OnDevice), harness.downloads.states(id).first())
        assertFalse(root().walk().any { it.name.startsWith(".pending-") })
    }

    @Test fun `a page is tried a bounded number of times and a failure that may pass keeps the chapter and holds its source (ADR 0027)`() = runTest {
        val harness = Harness(this)
        val attempts = mutableMapOf<String, Int>()
        harness.onFetch = { url ->
            val attempt = attempts.merge(url, 1, Int::plus)!!
            if (url == "$PAGES/c1/1" && attempt < SeriesDownloads.PAGE_ATTEMPTS || url == "$PAGES/c2/1") throw IOException("Lost")
        }
        harness.downloads.download(id, listOf("/c1", "/c2", "/c3"))

        assertEquals(DrainResult(downloaded = 1, retry = true), harness.downloads.drain(automatic = false))
        assertEquals(SeriesDownloads.PAGE_ATTEMPTS, attempts["$PAGES/c1/1"])
        assertEquals(SeriesDownloads.PAGE_ATTEMPTS, attempts["$PAGES/c2/1"])
        assertEquals(
            mapOf("/c1" to ChapterDownload.OnDevice, "/c2" to ChapterDownload.Queued(null), "/c3" to ChapterDownload.Queued(null)),
            harness.downloads.states(id).first(),
        )
        assertFalse(root().walk().any { it.name.startsWith(".pending-") })
    }

    @Test fun `a source that is off holds only its own chapters, which the drain reports as waiting (ADR 0027)`() = runTest {
        val elsewhere = SeriesId(SourceKey.parseOrThrow("custom_mihon_8"), "/title/2")
        val harness = Harness(this, others = listOf(StoredSeries(Series(elsewhere, "Other"))))
        harness.off = setOf(source)
        harness.downloads.download(id, listOf("/c1"))
        harness.downloads.download(elsewhere, listOf("/c2"))

        val result = harness.downloads.drain(automatic = false)

        assertEquals(DrainResult(downloaded = 1, waiting = listOf(QueuedChapter(id, "/c1"))), result)
        assertEquals(mapOf("/c1" to ChapterDownload.Queued(null)), harness.downloads.states(id).first())
        assertEquals(mapOf("/c2" to ChapterDownload.OnDevice), harness.downloads.states(elsewhere).first())
    }

    @Test fun `a source turned on while the drain fetches another source's chapter is tried again in the same drain`() = runTest {
        val elsewhere = SeriesId(SourceKey.parseOrThrow("custom_mihon_8"), "/title/2")
        val harness = Harness(this, others = listOf(StoredSeries(Series(elsewhere, "Other"))))
        val sources = MutableStateFlow(0)
        val watching = launch { harness.downloads.rescheduleOnChanges(sources, flowOf(Unit)) }
        harness.off = setOf(source)
        harness.onFetch = { url ->
            if (url == "$PAGES/c2/1") {
                harness.off = emptySet()
                sources.value = 1
                delay(1)
            }
        }
        harness.downloads.download(id, listOf("/c1"))
        harness.downloads.download(elsewhere, listOf("/c2"))

        assertEquals(DrainResult(downloaded = 2), harness.downloads.drain(automatic = false))
        watching.cancel()
    }

    @Test fun `an unavailable chapter leaves the queue and the lane goes on (ADR 0027)`() = runTest {
        val harness = Harness(this)
        harness.onFetch = { url -> if ("/c1/" in url) throw ChapterUnavailable("Gone") }
        harness.downloads.download(id, listOf("/c1", "/c9", "/c2"))

        val failed = listOf(ChapterName(id, "Title", "Ch. 1"), ChapterName(id, "Title", ""))
        assertEquals(DrainResult(downloaded = 1, failed = failed), harness.downloads.drain(automatic = false))
        assertEquals(mapOf("/c2" to ChapterDownload.OnDevice), harness.downloads.states(id).first())
    }

    @Test fun `a chapter its source answers with no pages, or a page without an address, leaves the queue`() = runTest {
        val harness = Harness(this)
        harness.mediaOf = { entry ->
            when (entry.address) {
                "/c1" -> EntryMedia.Pages(emptyList())
                "/c2" -> EntryMedia.Pages(listOf(ImageRef(url = null, localPath = null, mime = null)))
                else -> EntryMedia.Pages(twoPages(entry))
            }
        }
        harness.downloads.download(id, listOf("/c1", "/c2", "/c3"))

        assertEquals(2, harness.downloads.drain(automatic = false).failed.size)
        assertEquals(mapOf("/c3" to ChapterDownload.OnDevice), harness.downloads.states(id).first())
    }

    @Test fun `an entry its source answers with streams fails rather than downloading nothing`() = runTest {
        val harness = Harness(this)
        harness.mediaOf = { entry ->
            if (entry.address == "/c1") EntryMedia.Streams(listOf(MediaStream("https://video.test/1.m3u8", "1080p"))) else EntryMedia.Pages(twoPages(entry))
        }
        harness.downloads.download(id, listOf("/c1", "/c2"))

        assertEquals(1, harness.downloads.drain(automatic = false).failed.size)
        assertEquals(mapOf("/c2" to ChapterDownload.OnDevice), harness.downloads.states(id).first())
        assertTrue(harness.fetched.none { "video.test" in it })
    }

    @Test fun `delete after reading removes read chapters except where the series was last opened, also at start (ADR 0027)`() = runTest {
        val entries = (3 downTo 1).mapIndexed { order, number -> chapter(number, order, read = number != 3) }
        val stored = StoredSeries(Series(id, "Title"), lastOpenedEntry = "/c2")
        listOf("/c1", "/c2", "/c3").forEach { address -> store().saveChapter(id, address) }

        Harness(this, stored, entries).downloads.deleteRead()
        assertEquals(setOf("/c1", "/c2", "/c3"), Harness(this).downloads.downloaded(id))
        Harness(this, stored, entries, deleteAfterReading = true).downloads.resume()
        assertEquals(setOf("/c2", "/c3"), Harness(this).downloads.downloaded(id))
    }

    @Test fun `a queue left by a process that ended resumes`() = runTest {
        Harness(this).downloads.download(id, listOf("/c1"))

        val restarted = Harness(this)
        restarted.downloads.resume()
        assertEquals(listOf("ensure user"), restarted.scheduled)
        assertEquals(DrainResult(downloaded = 1), restarted.downloads.drain(automatic = false))
    }

    @Test fun `queuing a chapter finishes even when its caller is cancelled (ADR 0027)`() = runTest {
        val harness = Harness(this)

        launch(start = CoroutineStart.UNDISPATCHED) { harness.downloads.download(id, listOf("/c1")) }.cancel()
        advanceUntilIdle()

        assertEquals(listOf("schedule user"), harness.scheduled)
        assertEquals(DrainResult(downloaded = 1), Harness(this).downloads.drain(automatic = false))
    }

    @Test fun `deleting a chapter, a series or the whole queue finishes even when its caller is cancelled (ADR 0027)`() = runTest {
        val harness = Harness(this)
        val other = SeriesId(source, "/title/2")
        listOf(id to "/c1", id to "/c2", other to "/c1").forEach { (series, address) -> harness.store.saveChapter(series, address) }
        fun cancelled(block: suspend () -> Unit) = launch(start = CoroutineStart.UNDISPATCHED) { block() }.cancel()

        cancelled { harness.downloads.delete(id, "/c1") }
        advanceUntilIdle()
        assertEquals(setOf("/c2"), harness.downloads.downloaded(id))
        cancelled { harness.downloads.deleteSeries(id) }
        advanceUntilIdle()
        assertEquals(emptySet<String>(), harness.downloads.downloaded(id))
        harness.downloads.download(id, listOf("/c3"))
        val reopened = Harness(this)
        launch(start = CoroutineStart.UNDISPATCHED) { reopened.downloads.delete(other, "/c9") }
        cancelled { reopened.downloads.cancelAll() }
        advanceUntilIdle()
        assertEquals(emptySet<Boolean>(), reopened.downloads.queuedLanes().first())
        assertEquals(DrainResult(downloaded = 0), Harness(this).downloads.drain(automatic = false))
        assertEquals(setOf("/c1"), harness.downloads.downloaded(other))
    }

    @Test fun `a queued chapter whose source this build cannot read is dropped and the rest load (ADR 0027)`() = runTest {
        Harness(this).downloads.download(id, listOf("/c1"))
        val queue = folder.root.resolve("queue.json")
        val unreadable = """{"source":"not a source","series":"/title/9","entry":"/c9"},"""
        queue.writeText(queue.readText().replaceFirst("{\"source\"", unreadable + "{\"source\""))
        assertTrue(queue.readText().contains("not a source"))

        assertEquals(DrainResult(downloaded = 1), Harness(this).downloads.drain(automatic = false))
    }

    @Test fun `a chapter fetched while every offline copy is removed is not kept, and stays queued unless cancelled first (ADR 0027)`() = runTest {
        val harness = Harness(this)
        val held = CompletableDeferred<Unit>()
        harness.onFetch = { url -> if ("/c1/" in url) held.await() }
        harness.downloads.download(id, listOf("/c1", "/c2"))
        val drain = async { harness.downloads.drain(automatic = false) }
        advanceUntilIdle()

        harness.store.clear()
        held.complete(Unit)
        assertEquals(DrainResult(downloaded = 0, retry = true), drain.await())
        assertEquals(mapOf("/c1" to ChapterDownload.Queued(null), "/c2" to ChapterDownload.Queued(null)), harness.downloads.states(id).first())

        harness.downloads.cancelAll()
        harness.store.clear()
        assertEquals(emptyMap<String, ChapterDownload>(), harness.downloads.states(id).first())
        assertEquals(DrainResult(downloaded = 0), harness.downloads.drain(automatic = false))
    }

    @Test fun `each lane has its own job, and a running one never holds back the other (ADR 0027)`() = runTest {
        val harness = Harness(this)
        val held = CompletableDeferred<Unit>()
        harness.onFetch = { url -> if ("/c1/" in url) held.await() }
        harness.downloads.download(id, listOf("/c1"))
        val user = async { harness.downloads.drain(automatic = false) }
        advanceUntilIdle()

        harness.downloads.download(id, listOf("/c2"), automatic = true)
        assertEquals(listOf("schedule user", "schedule automatic"), harness.scheduled)
        held.complete(Unit)
        assertEquals(DrainResult(downloaded = 1), user.await())
        assertEquals(mapOf("/c1" to ChapterDownload.OnDevice, "/c2" to ChapterDownload.Queued(null)), harness.downloads.states(id).first())
        assertEquals(DrainResult(downloaded = 1), harness.downloads.drain(automatic = true))
    }

    @Test fun `the two lanes never fetch at the same time (ADR 0027)`() = runTest {
        val harness = Harness(this)
        val held = CompletableDeferred<Unit>()
        harness.onFetch = { url -> if ("/c1/" in url) held.await() }
        harness.downloads.download(id, listOf("/c1"))
        harness.downloads.download(id, listOf("/c2"), automatic = true)
        val user = async { harness.downloads.drain(automatic = false) }
        val automatic = async { harness.downloads.drain(automatic = true) }
        advanceUntilIdle()

        assertEquals(emptyList<String>(), harness.fetched)
        held.complete(Unit)
        assertEquals(DrainResult(downloaded = 1) to DrainResult(downloaded = 1), user.await() to automatic.await())
    }

    @Test fun `lanes are scheduled again when the sources change, and the user's lane, draining too, when the network settings do (ADR 0027, 0028)`() = runTest {
        val harness = Harness(this)
        val sources = MutableStateFlow(1)
        val network = MutableStateFlow(false)
        backgroundScope.launch { harness.downloads.rescheduleOnChanges(sources, network) }
        harness.downloads.download(id, listOf("/c1"))
        harness.downloads.download(id, listOf("/c2"), automatic = true)
        runCurrent()
        harness.scheduled.clear()

        network.value = true
        runCurrent()
        assertEquals(listOf("schedule user"), harness.scheduled)

        harness.scheduled.clear()
        sources.value = 2
        runCurrent()
        assertEquals(listOf("schedule user", "schedule automatic"), harness.scheduled)

        val held = CompletableDeferred<Unit>()
        harness.onFetch = { url -> if ("/c1/" in url) held.await() }
        val user = async { harness.downloads.drain(automatic = false) }
        runCurrent()
        harness.scheduled.clear()
        sources.value = 3
        runCurrent()
        assertEquals(listOf("schedule automatic"), harness.scheduled)
        harness.scheduled.clear()
        network.value = false
        runCurrent()
        assertEquals(listOf("schedule user"), harness.scheduled)
        held.complete(Unit)
        user.await()
    }

    @Test fun `a replaced drain stops without dropping its chapter, and leaves the lane to the drain that replaced it (ADR 0027, 0028)`() = runTest {
        val harness = Harness(this)
        val held = CompletableDeferred<Unit>()
        harness.onFetch = { url -> if ("/c1/" in url) held.await() }
        harness.downloads.download(id, listOf("/c1"))
        val replaced = launch { harness.downloads.drain(automatic = false) }
        advanceUntilIdle()

        val replacing = async { harness.downloads.drain(automatic = false) }
        advanceUntilIdle()
        replaced.cancel()
        advanceUntilIdle()
        assertEquals(mapOf("/c1" to ChapterDownload.Queued(0f)), harness.downloads.states(id).first())
        harness.scheduled.clear()
        harness.downloads.download(id, listOf("/c2"))
        assertEquals(emptyList<String>(), harness.scheduled)

        held.complete(Unit)
        assertEquals(DrainResult(downloaded = 2), replacing.await())
        assertFalse(root().walk().any { it.name.startsWith(".pending-") })
    }

    @Test fun `new chapters download by themselves only for a followed series set to (ADR 0027)`() = runTest {
        val update = SeriesUpdate(Series(id, "Title"), listOf(SeriesEntry("/c3", "Ch. 3"), SeriesEntry("/c2", "Ch. 2")))
        val off = Harness(this, StoredSeries(Series(id, "Title"), followed = true))
        off.downloads.downloadNew(update)
        assertEquals(emptyMap<String, ChapterDownload>(), off.downloads.states(id).first())

        val on = Harness(this, StoredSeries(Series(id, "Title"), followed = true, downloadNewChapters = true))
        on.downloads.downloadNew(update)
        on.downloads.drain(automatic = true)
        assertEquals(listOf("/c2", "/c2", "/c3", "/c3"), on.fetched.map { it.substringBeforeLast('/').removePrefix(PAGES) })
    }

    @Test fun `new episodes never download, whatever the anime's setting says (ADR 0027)`() = runTest {
        val anime = Series(SeriesId(SourceKey.parseOrThrow("custom_aniyomi_7"), "/anime/1"), "Anime")
        val harness = Harness(this, StoredSeries(anime, followed = true, downloadNewChapters = true))

        harness.downloads.downloadNew(SeriesUpdate(anime, listOf(SeriesEntry("/e2", "2"))))

        assertEquals(emptyMap<String, ChapterDownload>(), harness.downloads.states(anime.id).first())
        assertEquals(emptyList<String>(), harness.scheduled)
    }

    @Test fun `each menu choice picks its chapters oldest first, leaving out those already there`() {
        val entries = (9 downTo 1).mapIndexed { order, number -> chapter(number, order, read = number == 2) }
        val series = StoredSeries(Series(id, "Title"), lastOpenedEntry = "/c2")
        fun pick(choice: DownloadChoice) = chaptersToDownload(choice, series, entries, present = setOf("/c4"))

        assertEquals(listOf("/c3", "/c5", "/c6", "/c7", "/c8"), pick(DownloadChoice.NEXT_UNREAD))
        assertEquals(listOf("/c1", "/c3", "/c5", "/c6", "/c7", "/c8", "/c9"), pick(DownloadChoice.ALL_UNREAD))
        assertEquals(listOf("/c1", "/c2", "/c3", "/c5", "/c6", "/c7", "/c8", "/c9"), pick(DownloadChoice.ALL))
    }

    private fun chapter(number: Int, order: Int, read: Boolean = false) =
        StoredSeriesEntry(SeriesEntry("/c$number", "Ch. $number"), sourceOrder = order, firstSeenAtEpochMs = 0, read = read)

    private fun root() = folder.root.resolve("downloads")

    private fun TestScope.store() = OfflineMediaStore(root(), StandardTestDispatcher(testScheduler))

    private suspend fun OfflineMediaStore.saveChapter(series: SeriesId, address: String) {
        val entry = SeriesEntry(address, address)
        val pages = twoPages(entry)
        save(chapterPost(Series(series, "Title"), entry, null, pages), pages, seriesOfflineOwner(series)) { _, file ->
            file.writeBytes(PAGE)
            "image/png"
        }
    }

    private inner class Harness(
        scope: TestScope,
        stored: StoredSeries = StoredSeries(Series(id, "Title")),
        entries: List<StoredSeriesEntry> = chapters,
        others: List<StoredSeries> = emptyList(),
        deleteAfterReading: Boolean = false,
    ) {
        val scheduled = mutableListOf<String>()
        val fetched = mutableListOf<String>()
        var onFetch: suspend (String) -> Unit = {}
        var mediaOf: (SeriesEntry) -> EntryMedia = { EntryMedia.Pages(twoPages(it)) }

        var off: Set<SourceKey> = emptySet()
        val store = scope.store()
        val downloads = SeriesDownloads(
            store = store,
            queueFile = folder.root.resolve("queue.json"),
            repository = Stored(listOf(stored) + others, entries),
            source = { key -> FakeSource(key) { mediaOf(it) }.takeIf { key !in off } },
            covers = { series -> series.coverUrl?.let { "$it#loadable" } },
            fetch = { url, file ->
                onFetch(url)
                fetched += url
                file.writeBytes(PAGE)
                "image/png"
            },
            scheduler = object : DownloadScheduler {
                override suspend fun schedule(automatic: Boolean) {
                    yield()
                    scheduled += "schedule ${if (automatic) "automatic" else "user"}"
                }

                override suspend fun ensureScheduled(automatic: Boolean) {
                    scheduled += "ensure ${if (automatic) "automatic" else "user"}"
                }
            },
            deleteAfterReading = { deleteAfterReading },
            files = AtomicJsonFileStore(StandardTestDispatcher(scope.testScheduler)),
        )
    }

    private companion object {
        const val PAGES = "https://pages.test"
        val PAGE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    }

    private fun twoPages(entry: SeriesEntry) = (1..2).map { ImageRef(url = "$PAGES${entry.address}/$it", localPath = null, mime = null) }

    private class FakeSource(override val key: SourceKey, private val mediaOf: (SeriesEntry) -> EntryMedia) : SeriesSource {
        override val name = "Fake"
        override val language = "en"
        override suspend fun media(entry: SeriesEntry) = mediaOf(entry)
        override suspend fun popular(page: Int): SeriesPage = error("unused")
        override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage = error("unused")
        override suspend fun details(series: Series): Series = series
        override suspend fun entries(series: Series, stored: List<SeriesEntry>) = SeriesEntries(emptyList())
    }

    private class Stored(private val stored: List<StoredSeries>, private val entries: List<StoredSeriesEntry>) : SeriesRepository {
        override suspend fun series(id: SeriesId) = stored.firstOrNull { it.series.id == id }
        override suspend fun entries(id: SeriesId) = entries
        override fun observeFollowed(): Flow<List<FollowedSeries>> = emptyFlow()
        override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = emptyFlow()
        override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = emptyFlow()
        override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
        override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
        override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>, seriesMemo: String?) = emptyList<SeriesEntry>()
        override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
        override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
        override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
        override suspend fun setChaptersShareFirstPage(id: SeriesId) = Unit
        override suspend fun setAllRead(id: SeriesId, read: Boolean) = EntryProgressRestore()
        override suspend fun setEntriesRead(id: SeriesId, entryAddresses: List<String>, read: Boolean) = EntryProgressRestore()
        override suspend fun restoreProgress(id: SeriesId, restore: EntryProgressRestore) = Unit
        override suspend fun recordProgress(id: SeriesId, entryAddress: String, position: Long, length: Long?) = Unit
    }
}
