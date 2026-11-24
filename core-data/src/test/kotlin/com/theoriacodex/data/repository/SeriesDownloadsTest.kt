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

    /** Chapters 1 to 3, listed newest first as sources do. */
    private val chapters = (3 downTo 1).mapIndexed { order, number -> chapter(number, order) }

    @Test fun `deleting a chapter or a series leaves the rest`() = runTest {
        val harness = Harness(this)
        val other = SeriesId(source, "/title/2")
        listOf(id to "/c1", id to "/c2", other to "/c1").forEach { (series, address) -> harness.store.saveChapter(series, address) }

        harness.downloads.delete(id, "/c1")
        assertEquals(setOf("/c2"), harness.downloads.downloaded(id))
        harness.downloads.deleteSeries(id)
        assertEquals(emptySet<String>(), harness.downloads.downloaded(id))
        assertEquals(setOf("/c1"), Harness(this).downloads.downloaded(other))
    }

    @Test fun `the files decide what is downloaded whatever the database or a lost queue says`() = runTest {
        val harness = Harness(this)
        // A restored database lists every chapter, but nothing is on the device.
        assertEquals(emptyMap<String, ChapterDownload>(), harness.downloads.states(id).first())

        // Files a profile restore finds show as downloaded with no queue or database record of them.
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

        // Offline, the first page is the preview; the chapter as its source gave it keeps the cover.
        val saved = harness.store.withoutOfflineLocations(checkNotNull(harness.downloads.chapter(id, "/c1")))
        assertEquals("https://cover.test/1.jpg#loadable", saved.preview.url)
    }

    @Test fun `a page whose address names no type is saved under the type its fetch found`() = runTest {
        val harness = Harness(this)
        harness.downloads.download(id, listOf("/c1"))
        harness.downloads.drain(automatic = false)

        val pages = checkNotNull(harness.downloads.chapter(id, "/c1")).media
        assertEquals(listOf("image/png", "image/png"), pages.map { it.mime })
        assertTrue(pages.all { checkNotNull(it.localPath).endsWith(".png") })
    }

    @Test fun `a chapter already kept offline, such as in a Codex, is not fetched again`() = runTest {
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

    @Test fun `a page is tried a bounded number of times and a failure that may pass keeps the chapter and holds its source`() = runTest {
        val harness = Harness(this)
        val attempts = mutableMapOf<String, Int>()
        harness.onFetch = { url ->
            val attempt = attempts.merge(url, 1, Int::plus)!!
            // Chapter 1's first page arrives on its last try; chapter 2's never does.
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

    @Test fun `a source that is off holds only its own chapters, which the drain reports as waiting`() = runTest {
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
                // Lets the change reach the downloads before this chapter finishes.
                delay(1)
            }
        }
        harness.downloads.download(id, listOf("/c1"))
        harness.downloads.download(elsewhere, listOf("/c2"))

        assertEquals(DrainResult(downloaded = 2), harness.downloads.drain(automatic = false))
        watching.cancel()
    }

    @Test fun `an unavailable chapter leaves the queue and the lane goes on`() = runTest {
        val harness = Harness(this)
        harness.onFetch = { url -> if ("/c1/" in url) throw ChapterUnavailable("Gone") }
        // Chapter 9 is not one the source lists.
        harness.downloads.download(id, listOf("/c1", "/c9", "/c2"))

        // The chapter the source does not list is named as nothing.
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

    @Test fun `delete after reading removes read chapters except where the series was last opened, also at start`() = runTest {
        // Chapters 1 and 2 are read, and the series was last opened at 2.
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

    @Test fun `a chapter fetched while every offline copy is removed is not kept, and stays queued unless cancelled first`() = runTest {
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

    @Test fun `each lane has its own job, and a running one never holds back the other`() = runTest {
        val harness = Harness(this)
        val held = CompletableDeferred<Unit>()
        harness.onFetch = { url -> if ("/c1/" in url) held.await() }
        harness.downloads.download(id, listOf("/c1"))
        val user = async { harness.downloads.drain(automatic = false) }
        advanceUntilIdle()

        // While the user's lane runs, a chapter the check queues still gets the job that waits for an unmetered network.
        harness.downloads.download(id, listOf("/c2"), automatic = true)
        assertEquals(listOf("schedule user", "schedule automatic"), harness.scheduled)
        held.complete(Unit)
        // The user's job leaves it to that one.
        assertEquals(DrainResult(downloaded = 1), user.await())
        assertEquals(mapOf("/c1" to ChapterDownload.OnDevice, "/c2" to ChapterDownload.Queued(null)), harness.downloads.states(id).first())
        assertEquals(DrainResult(downloaded = 1), harness.downloads.drain(automatic = true))
    }

    @Test fun `lanes are scheduled again when the sources change, and the user's lane, draining too, when the network settings do`() = runTest {
        val harness = Harness(this)
        val sources = MutableStateFlow(1)
        val network = MutableStateFlow(false)
        // runCurrent, not advanceUntilIdle: the latter stops once only backgroundScope's work is left.
        backgroundScope.launch { harness.downloads.rescheduleOnChanges(sources, network) }
        harness.downloads.download(id, listOf("/c1"))
        harness.downloads.download(id, listOf("/c2"), automatic = true)
        runCurrent()
        harness.scheduled.clear()

        // Only the user's job follows the network settings.
        network.value = true
        runCurrent()
        assertEquals(listOf("schedule user"), harness.scheduled)

        harness.scheduled.clear()
        sources.value = 2
        runCurrent()
        assertEquals(listOf("schedule user", "schedule automatic"), harness.scheduled)

        // A sources change leaves a draining lane its running job; a network change replaces it.
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

    @Test fun `a replaced drain stops without dropping its chapter, and leaves the lane to the drain that replaced it`() = runTest {
        val harness = Harness(this)
        val held = CompletableDeferred<Unit>()
        harness.onFetch = { url -> if ("/c1/" in url) held.await() }
        harness.downloads.download(id, listOf("/c1"))
        val replaced = launch { harness.downloads.drain(automatic = false) }
        advanceUntilIdle()

        // The system starts the replacing job before the replaced one has stopped.
        val replacing = async { harness.downloads.drain(automatic = false) }
        advanceUntilIdle()
        replaced.cancel()
        advanceUntilIdle()
        // Chapter 1 is still queued, now fetched by the replacing drain.
        assertEquals(mapOf("/c1" to ChapterDownload.Queued(0f)), harness.downloads.states(id).first())
        // The lane still counts as draining, so a chapter queued now joins the running drain instead of replacing its job.
        harness.scheduled.clear()
        harness.downloads.download(id, listOf("/c2"))
        assertEquals(emptyList<String>(), harness.scheduled)

        held.complete(Unit)
        assertEquals(DrainResult(downloaded = 2), replacing.await())
        assertFalse(root().walk().any { it.name.startsWith(".pending-") })
    }

    @Test fun `new chapters download by themselves only for a followed series set to`() = runTest {
        val update = SeriesUpdate(Series(id, "Title"), listOf(SeriesEntry("/c3", "Ch. 3"), SeriesEntry("/c2", "Ch. 2")))
        val off = Harness(this, StoredSeries(Series(id, "Title"), followed = true))
        off.downloads.downloadNew(update)
        assertEquals(emptyMap<String, ChapterDownload>(), off.downloads.states(id).first())

        val on = Harness(this, StoredSeries(Series(id, "Title"), followed = true, downloadNewChapters = true))
        on.downloads.downloadNew(update)
        on.downloads.drain(automatic = true)
        // Oldest first.
        assertEquals(listOf("/c2", "/c2", "/c3", "/c3"), on.fetched.map { it.substringBeforeLast('/').removePrefix(PAGES) })
    }

    @Test fun `new episodes never download, whatever the anime's setting says`() = runTest {
        val anime = Series(SeriesId(SourceKey.parseOrThrow("custom_aniyomi_7"), "/anime/1"), "Anime")
        val harness = Harness(this, StoredSeries(anime, followed = true, downloadNewChapters = true))

        harness.downloads.downloadNew(SeriesUpdate(anime, listOf(SeriesEntry("/e2", "2"))))

        assertEquals(emptyMap<String, ChapterDownload>(), harness.downloads.states(anime.id).first())
        assertEquals(emptyList<String>(), harness.scheduled)
    }

    @Test fun `each menu choice picks its chapters oldest first, leaving out those already there`() {
        // Chapter 1 was skipped, 2 is read and was opened last, so Continue is at 3; 4 is already downloaded.
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

    /** Puts the chapter at [address] of [series] on the device, as a finished download leaves it. */
    private suspend fun OfflineMediaStore.saveChapter(series: SeriesId, address: String) {
        val entry = SeriesEntry(address, address)
        val pages = twoPages(entry)
        save(chapterPost(Series(series, "Title"), entry, null, pages), pages, seriesOfflineOwner(series)) { _, file ->
            file.writeBytes(PAGE)
            "image/png"
        }
    }

    /** Downloads over this test's folder, with a source of two pages per chapter unless [mediaOf] says otherwise, and a fetch that records each page. */
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

        /** Sources that are off; every other source is on. */
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

    /** Two pages, at addresses naming the chapter. */
    private fun twoPages(entry: SeriesEntry) = (1..2).map { ImageRef(url = "$PAGES${entry.address}/$it", localPath = null, mime = null) }

    /** A source whose entries open as [mediaOf] gives. */
    private class FakeSource(override val key: SourceKey, private val mediaOf: (SeriesEntry) -> EntryMedia) : SeriesSource {
        override val name = "Fake"
        override val language = "en"
        override suspend fun media(entry: SeriesEntry) = mediaOf(entry)
        override suspend fun popular(page: Int): SeriesPage = error("unused")
        override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage = error("unused")
        override suspend fun details(series: Series): Series = series
        override suspend fun entries(series: Series, stored: List<SeriesEntry>) = SeriesEntries(emptyList())
    }

    /** A repository that only answers what is stored; downloads need nothing else. */
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
