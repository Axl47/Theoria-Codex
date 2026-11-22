package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun `a chapter is on the device only once its whole write finished`() = runTest {
        val store = store()
        assertTrue(runCatching { store.write(id, "/c1") { staging -> page(staging, 0); throw IOException("Lost") } }.isFailure)
        assertNull(store.pages(id, "/c1"))
        assertEquals(emptySet<String>(), store.downloaded(id))

        store.write(id, "/c1") { staging -> listOf(page(staging, 0), page(staging, 1)) }
        val pages = checkNotNull(store.pages(id, "/c1"))
        assertEquals(listOf("0001.png", "0002.png"), pages.map { File(checkNotNull(it.localPath)).name })
        assertEquals(listOf("image/png", "image/png"), pages.map { it.mime })
        assertTrue(pages.all { it.url == null })
        assertFalse(root().walk().any { it.name.startsWith(".partial-") })
    }

    @Test fun `a cancelled write never appears, even with every page in`() = runTest {
        val store = store()
        val write = launch { store.write(id, "/c1") { staging -> listOf(page(staging, 0)).also { coroutineContext.cancel() } } }
        write.join()
        assertTrue(write.isCancelled)
        assertEquals(emptySet<String>(), store.downloaded(id))
        assertFalse(root().walk().any { it.name.startsWith(".partial-") })
    }

    @Test fun `a chapter whose files do not match its marker is not on the device`() = runTest {
        val store = store()
        listOf("/c1", "/c2", "/c3").forEach { address -> store.write(id, address) { staging -> listOf(page(staging, 0), page(staging, 1)) } }

        // What a disk that lost data, a delete from outside the app, or a delete midway through this read can leave: a
        // page missing, a page cut short, no marker.
        File(chapterFolder("/c1"), "0002.png").delete()
        File(chapterFolder("/c2"), "0001.png").writeBytes(byteArrayOf(1))
        File(chapterFolder("/c3"), "chapter.json").delete()

        assertEquals(listOf(null, null, null), listOf("/c1", "/c2", "/c3").map { store.pages(id, it) })
        // As the files are read when the process starts.
        assertEquals(emptySet<String>(), store().downloaded(id))
    }

    @Test fun `what stopped downloads and deletes leave is swept`() = runTest {
        val store = store()
        store.write(id, "/c1") { staging -> listOf(page(staging, 0)) }
        val seriesFolder = root().listFiles().orEmpty().single()
        // Left by a process that ended mid-download, mid-delete of a chapter, and mid-delete of a series.
        listOf(File(seriesFolder, ".partial-a"), File(seriesFolder, ".deleted-b"), File(root(), ".deleted-c")).forEach {
            it.mkdirs()
            it.resolve("chapter.json").writeText("""{"address":"/c2","pages":[]}""")
        }
        assertEquals(setOf("/c1"), store.downloaded(id))
        store.removeUnfinished()
        assertEquals(listOf(seriesFolder.name), root().list().orEmpty().toList())
        assertEquals(setOf("/c1"), store.downloaded(id))
    }

    @Test fun `deleting a chapter, a series or everything leaves the rest and the size follows`() = runTest {
        val store = store()
        val other = SeriesId(source, "/title/2")
        listOf(id to "/c1", id to "/c2", other to "/c1").forEach { (series, entry) -> store.write(series, entry) { listOf(page(it, 0)) } }
        assertTrue(store.sizeBytes() >= 3 * PAGE.size)

        store.delete(id, "/c1")
        assertEquals(setOf("/c2"), store.downloaded(id))
        store.delete(id, "/c2")
        // The series' folder goes with its last chapter.
        assertEquals(1, root().list().orEmpty().size)
        store.write(id, "/c3") { listOf(page(it, 0)) }
        store.deleteSeries(id)
        assertEquals(emptySet<String>(), store.downloaded(id))
        assertEquals(setOf("/c1"), store.downloaded(other))
        store.deleteAll()
        assertEquals(0L, store.sizeBytes())
        assertEquals(emptyMap<SeriesId, Set<String>>(), store().downloaded())
    }

    @Test fun `the files decide what is downloaded whatever the database or a lost queue says`() = runTest {
        val harness = Harness(this)
        // A restored database lists every chapter, but nothing is on the device.
        assertEquals(emptyMap<String, ChapterDownload>(), harness.downloads.states(id).first())

        // Files a profile restore finds show as downloaded with no queue or database record of them.
        harness.store.write(id, "/c2") { listOf(page(it, 0)) }
        assertEquals(mapOf("/c2" to ChapterDownload.OnDevice), Harness(this).downloads.states(id).first())
    }

    @Test fun `the queue downloads in order, one chapter at a time`() = runTest {
        val harness = Harness(this)
        harness.downloads.download(id, listOf("/c1", "/c2", "/c3"))
        assertEquals(listOf("schedule user"), harness.scheduled)

        assertEquals(DrainResult(downloaded = 3, failed = 0), harness.downloads.drain(automatic = false))
        assertEquals(listOf("/c1", "/c1", "/c2", "/c2", "/c3", "/c3"), harness.fetched.map { it.substringBeforeLast('/').removePrefix(PAGES) })
        assertEquals(setOf("/c1", "/c2", "/c3"), harness.store.downloaded(id))
        assertEquals(emptyMap<String, ChapterDownload>() + chapters.associate { it.entry.address to ChapterDownload.OnDevice }, harness.downloads.states(id).first())
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

        assertEquals(DrainResult(downloaded = 1, failed = 0), drain.await())
        assertEquals(mapOf("/c2" to ChapterDownload.OnDevice), harness.downloads.states(id).first())
        assertFalse(root().walk().any { it.name.startsWith(".partial-") })
    }

    @Test fun `a page is tried a bounded number of times and a failure that may pass keeps the chapter and stops the lane`() = runTest {
        val harness = Harness(this)
        val attempts = mutableMapOf<String, Int>()
        harness.onFetch = { url ->
            val attempt = attempts.merge(url, 1, Int::plus)!!
            // Chapter 1's first page arrives on its last try; chapter 2's never does.
            if (url == "$PAGES/c1/1" && attempt < SeriesDownloads.PAGE_ATTEMPTS || url == "$PAGES/c2/1") throw IOException("Lost")
        }
        harness.downloads.download(id, listOf("/c1", "/c2", "/c3"))

        assertEquals(DrainResult(downloaded = 1, failed = 0, retry = true), harness.downloads.drain(automatic = false))
        assertEquals(SeriesDownloads.PAGE_ATTEMPTS, attempts["$PAGES/c1/1"])
        assertEquals(SeriesDownloads.PAGE_ATTEMPTS, attempts["$PAGES/c2/1"])
        assertEquals(
            mapOf("/c1" to ChapterDownload.OnDevice, "/c2" to ChapterDownload.Queued(null), "/c3" to ChapterDownload.Queued(null)),
            harness.downloads.states(id).first(),
        )
        assertFalse(root().walk().any { it.name.startsWith(".partial-") })
    }

    @Test fun `an unavailable chapter leaves the queue and the lane goes on`() = runTest {
        val harness = Harness(this)
        harness.onFetch = { url -> if ("/c1/" in url) throw ChapterUnavailable("Gone") }
        // Chapter 9 is not one the source lists.
        harness.downloads.download(id, listOf("/c1", "/c9", "/c2"))

        assertEquals(DrainResult(downloaded = 1, failed = 2), harness.downloads.drain(automatic = false))
        assertEquals(mapOf("/c2" to ChapterDownload.OnDevice), harness.downloads.states(id).first())
    }

    @Test fun `a chapter its source answers with no pages, or a page without an address, leaves the queue`() = runTest {
        val harness = Harness(this)
        harness.pagesOf = { entry ->
            when (entry.address) {
                "/c1" -> emptyList()
                "/c2" -> listOf(ImageRef(url = null, localPath = null, mime = null))
                else -> twoPages(entry)
            }
        }
        harness.downloads.download(id, listOf("/c1", "/c2", "/c3"))

        assertEquals(DrainResult(downloaded = 1, failed = 2), harness.downloads.drain(automatic = false))
        assertEquals(mapOf("/c3" to ChapterDownload.OnDevice), harness.downloads.states(id).first())
    }

    @Test fun `delete after reading removes read chapters except where the series was last opened, also at start`() = runTest {
        // Chapters 1 and 2 are read, and the series was last opened at 2.
        val entries = (3 downTo 1).mapIndexed { order, number -> chapter(number, order, read = number != 3) }
        val stored = StoredSeries(Series(id, "Title"), lastOpenedEntry = "/c2")
        listOf("/c1", "/c2", "/c3").forEach { address -> store().write(id, address) { listOf(page(it, 0)) } }

        Harness(this, stored, entries).downloads.deleteRead()
        assertEquals(setOf("/c1", "/c2", "/c3"), store().downloaded(id))
        Harness(this, stored, entries, deleteAfterReading = true).downloads.resume()
        assertEquals(setOf("/c2", "/c3"), store().downloaded(id))
    }

    @Test fun `a queue left by a process that ended resumes and its leftovers go`() = runTest {
        Harness(this).downloads.download(id, listOf("/c1"))
        File(root(), "series/.partial-left").mkdirs()

        val restarted = Harness(this)
        restarted.downloads.resume()
        assertEquals(listOf("ensure user"), restarted.scheduled)
        assertFalse(File(root(), "series/.partial-left").exists())
        assertEquals(DrainResult(downloaded = 1, failed = 0), restarted.downloads.drain(automatic = false))
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
        assertEquals(DrainResult(downloaded = 1, failed = 0), user.await())
        assertEquals(mapOf("/c1" to ChapterDownload.OnDevice, "/c2" to ChapterDownload.Queued(null)), harness.downloads.states(id).first())
        assertEquals(DrainResult(downloaded = 1, failed = 0), harness.downloads.drain(automatic = true))
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

    /** The folder of the downloaded chapter at [address]. */
    private fun chapterFolder(address: String) =
        root().walk().single { it.name == "chapter.json" && "\"address\":\"$address\"" in it.readText() }.parentFile

    private fun TestScope.store() = SeriesDownloadStore(root(), StandardTestDispatcher(testScheduler))

    private fun page(staging: File, index: Int) = File(staging, SeriesDownloadStore.pageFileName(index, "png")).apply { writeBytes(PAGE) }

    /** Downloads over this test's folder, with a source of two pages per chapter unless [pagesOf] says otherwise, and a fetch that records each page. */
    private inner class Harness(
        scope: TestScope,
        stored: StoredSeries = StoredSeries(Series(id, "Title")),
        entries: List<StoredSeriesEntry> = chapters,
        deleteAfterReading: Boolean = false,
    ) {
        val scheduled = mutableListOf<String>()
        val fetched = mutableListOf<String>()
        var onFetch: suspend (String) -> Unit = {}
        var pagesOf: (SeriesEntry) -> List<ImageRef> = ::twoPages
        val store = scope.store()
        val downloads = SeriesDownloads(
            store = store,
            queueFile = folder.root.resolve("queue.json"),
            repository = Stored(stored, entries),
            sources = { listOf(FakeSource(source) { pagesOf(it) }) },
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

    /** A source whose chapters have the pages [pagesOf] gives. */
    private class FakeSource(override val key: SourceKey, private val pagesOf: (SeriesEntry) -> List<ImageRef>) : SeriesSource {
        override val name = "Fake"
        override val language = "en"
        override suspend fun pages(entry: SeriesEntry) = pagesOf(entry)
        override suspend fun popular(page: Int): SeriesPage = error("unused")
        override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage = error("unused")
        override suspend fun details(series: Series): Series = series
        override suspend fun entries(series: Series): List<SeriesEntry> = emptyList()
    }

    /** A repository that only answers what is stored; downloads need nothing else. */
    private class Stored(private val stored: StoredSeries, private val entries: List<StoredSeriesEntry>) : SeriesRepository {
        override suspend fun series(id: SeriesId) = stored.takeIf { it.series.id == id }
        override suspend fun entries(id: SeriesId) = entries
        override fun observeFollowed(): Flow<List<FollowedSeries>> = emptyFlow()
        override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = emptyFlow()
        override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = emptyFlow()
        override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
        override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
        override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>) = emptyList<SeriesEntry>()
        override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
        override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
        override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
        override suspend fun recordProgress(id: SeriesId, entryAddress: String, page: Int, pageCount: Int?) = Unit
    }
}
