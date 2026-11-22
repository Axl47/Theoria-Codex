package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

        store.write(id, "/c1") { staging -> (1 downTo 0).forEach { page(staging, it) } }
        val pages = checkNotNull(store.pages(id, "/c1"))
        assertEquals(listOf("0001.png", "0002.png"), pages.map { File(checkNotNull(it.localPath)).name })
        assertEquals(listOf("image/png", "image/png"), pages.map { it.mime })
        assertTrue(pages.all { it.url == null })

        // What a process that died right before the rename leaves: complete pages and address, under the staging name.
        val seriesFolder = root().listFiles().orEmpty().single()
        File(seriesFolder, ".partial-left").apply { mkdirs() }.resolve("address").writeText("/c2")
        assertEquals(setOf("/c1"), store.downloaded(id))
        store.removeUnfinished()
        assertEquals(listOf("/c1"), seriesFolder.walk().filter { it.name == "address" }.map { it.readText() }.toList())
    }

    @Test fun `deleting a chapter, a series or everything leaves the rest and the size follows`() = runTest {
        val store = store()
        val other = SeriesId(source, "/title/2")
        listOf(id to "/c1", id to "/c2", other to "/c1").forEach { (series, entry) -> store.write(series, entry) { page(it, 0) } }
        assertTrue(store.sizeBytes() >= 3 * PAGE.size)

        store.delete(id, "/c1")
        assertEquals(setOf("/c2"), store.downloaded(id))
        store.deleteSeries(id)
        assertEquals(emptySet<String>(), store.downloaded(id))
        assertEquals(setOf("/c1"), store.downloaded(other))
        store.deleteAll()
        assertEquals(0L, store.sizeBytes())
    }

    @Test fun `the files decide what is downloaded whatever the database or a lost queue says`() = runTest {
        val harness = Harness(this)
        // A restored database lists every chapter, but nothing is on the device.
        assertEquals(emptyMap<String, ChapterDownload>(), harness.downloads.states(id).first())

        // Files a profile restore finds show as downloaded with no queue or database record of them.
        harness.store.write(id, "/c2") { page(it, 0) }
        assertEquals(mapOf("/c2" to ChapterDownload.OnDevice), Harness(this).downloads.states(id).first())
    }

    @Test fun `the queue downloads in order, one chapter at a time`() = runTest {
        val harness = Harness(this)
        harness.downloads.download(id, listOf("/c1", "/c2", "/c3"))
        assertEquals(listOf("schedule user"), harness.scheduled)

        assertEquals(DrainResult(downloaded = 3, failed = 0), harness.downloads.drain(unmetered = { false }))
        assertEquals(listOf("/c1", "/c1", "/c2", "/c2", "/c3", "/c3"), harness.fetched.map { it.substringBeforeLast('/').removePrefix(PAGES) })
        assertEquals(setOf("/c1", "/c2", "/c3"), harness.store.downloaded(id))
        assertEquals(emptyMap<String, ChapterDownload>() + chapters.associate { it.entry.address to ChapterDownload.OnDevice }, harness.downloads.states(id).first())
    }

    @Test fun `cancelling the chapter being fetched stops it and the queue goes on`() = runTest {
        val harness = Harness(this)
        val never = CompletableDeferred<Unit>()
        harness.onFetch = { url -> if ("/c1/" in url) never.await() }
        harness.downloads.download(id, listOf("/c1", "/c2"))

        val drain = async { harness.downloads.drain(unmetered = { true }) }
        advanceUntilIdle()
        assertEquals(ChapterDownload.Queued(0f), harness.downloads.states(id).first()["/c1"])
        harness.downloads.cancel(id, "/c1")

        assertEquals(DrainResult(downloaded = 1, failed = 0), drain.await())
        assertEquals(setOf("/c2"), harness.store.downloaded(id))
        assertFalse(root().walk().any { it.name.startsWith(".partial-") })
    }

    @Test fun `a page is tried a bounded number of times and a chapter that still fails is not downloaded`() = runTest {
        val harness = Harness(this)
        val attempts = mutableMapOf<String, Int>()
        harness.onFetch = { url ->
            val attempt = attempts.merge(url, 1, Int::plus)!!
            // Chapter 1's first page arrives on its last try; chapter 2's never does.
            if (url == "$PAGES/c1/1" && attempt < SeriesDownloads.PAGE_ATTEMPTS || url == "$PAGES/c2/1") throw IOException("Lost")
        }
        harness.downloads.download(id, listOf("/c1", "/c2"))

        assertEquals(DrainResult(downloaded = 1, failed = 1), harness.downloads.drain(unmetered = { true }))
        assertEquals(SeriesDownloads.PAGE_ATTEMPTS, attempts["$PAGES/c1/1"])
        assertEquals(SeriesDownloads.PAGE_ATTEMPTS, attempts["$PAGES/c2/1"])
        assertEquals(mapOf("/c1" to ChapterDownload.OnDevice), harness.downloads.states(id).first())
        assertFalse(root().walk().any { it.name.startsWith(".partial-") })
    }

    @Test fun `a queue left by a process that ended resumes and its leftovers go`() = runTest {
        Harness(this).downloads.download(id, listOf("/c1"))
        File(root(), "series/.partial-left").mkdirs()

        val restarted = Harness(this)
        restarted.downloads.resume()
        assertEquals(listOf("ensure user"), restarted.scheduled)
        assertFalse(File(root(), "series/.partial-left").exists())
        assertEquals(DrainResult(downloaded = 1, failed = 0), restarted.downloads.drain(unmetered = { true }))
    }

    @Test fun `chapters the check queued wait for an unmetered network`() = runTest {
        val harness = Harness(this)
        harness.downloads.download(id, listOf("/c1"), automatic = true)
        assertEquals(listOf("schedule automatic"), harness.scheduled)

        assertEquals(DrainResult(downloaded = 0, failed = 0), harness.downloads.drain(unmetered = { false }))
        assertEquals(mapOf("/c1" to ChapterDownload.Queued(null)), harness.downloads.states(id).first())
        // Left behind by a drain on a metered network, they get a run of their own.
        assertEquals(listOf("schedule automatic", "schedule automatic"), harness.scheduled)

        assertEquals(DrainResult(downloaded = 1, failed = 0), harness.downloads.drain(unmetered = { true }))
    }

    @Test fun `new chapters download by themselves only for a followed series set to`() = runTest {
        val update = SeriesUpdate(Series(id, "Title"), listOf(SeriesEntry("/c3", "Ch. 3"), SeriesEntry("/c2", "Ch. 2")))
        val off = Harness(this, StoredSeries(Series(id, "Title"), followed = true))
        off.downloads.downloadNew(update)
        assertEquals(emptyMap<String, ChapterDownload>(), off.downloads.states(id).first())

        val on = Harness(this, StoredSeries(Series(id, "Title"), followed = true, downloadNewChapters = true))
        on.downloads.downloadNew(update)
        on.downloads.drain(unmetered = { true })
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

    private fun TestScope.store() = SeriesDownloadStore(root(), StandardTestDispatcher(testScheduler))

    private fun page(staging: File, index: Int) = File(staging, SeriesDownloadStore.pageFileName(index, "png")).writeBytes(PAGE)

    /** Downloads over this test's folder, with a source of two pages per chapter and a fetch that records each page. */
    private inner class Harness(scope: TestScope, stored: StoredSeries = StoredSeries(Series(id, "Title"))) {
        val scheduled = mutableListOf<String>()
        val fetched = mutableListOf<String>()
        var onFetch: suspend (String) -> Unit = {}
        val store = scope.store()
        val downloads = SeriesDownloads(
            store = store,
            queueFile = folder.root.resolve("queue.json"),
            repository = Stored(stored, chapters),
            sources = { listOf(TwoPages(source)) },
            fetch = { url, file ->
                onFetch(url)
                fetched += url
                file.writeBytes(PAGE)
                "image/png"
            },
            scheduler = object : DownloadScheduler {
                override fun schedule(automatic: Boolean) {
                    scheduled += "schedule ${if (automatic) "automatic" else "user"}"
                }

                override fun ensureScheduled(automatic: Boolean) {
                    scheduled += "ensure ${if (automatic) "automatic" else "user"}"
                }
            },
            files = AtomicJsonFileStore(StandardTestDispatcher(scope.testScheduler)),
        )
    }

    private companion object {
        const val PAGES = "https://pages.test"
        val PAGE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    }

    /** A source whose every chapter has two pages, at addresses naming the chapter. */
    private class TwoPages(override val key: SourceKey) : SeriesSource {
        override val name = "Fake"
        override val language = "en"
        override suspend fun pages(entry: SeriesEntry) = (1..2).map { ImageRef(url = "$PAGES${entry.address}/$it", localPath = null, mime = null) }
        override suspend fun popular(page: Int): SeriesPage = error("unused")
        override suspend fun search(text: String, page: Int): SeriesPage = error("unused")
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
        override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>) = emptyList<SeriesEntry>()
        override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
        override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
        override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
        override suspend fun recordProgress(id: SeriesId, entryAddress: String, page: Int, pageCount: Int?) = Unit
    }
}
