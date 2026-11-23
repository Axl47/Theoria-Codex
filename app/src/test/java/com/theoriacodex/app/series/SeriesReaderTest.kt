package com.theoriacodex.app.series

import com.theoriacodex.app.di.SeriesDependencies
import com.theoriacodex.app.media.isLikelyImageLocation
import com.theoriacodex.app.viewer.ViewerStripMark
import com.theoriacodex.app.viewer.decodeViewerPostId
import com.theoriacodex.app.viewer.encodeViewerPostId
import com.theoriacodex.data.repository.DownloadScheduler
import com.theoriacodex.data.repository.EntryProgressRestore
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.data.repository.SeriesDownloads
import com.theoriacodex.data.repository.chapterPost
import com.theoriacodex.data.repository.seriesOfflineOwner
import com.theoriacodex.data.storage.AtomicJsonFileStore
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSourceSettings
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.SeriesExtensions
import kotlinx.coroutines.flow.MutableStateFlow

class SeriesReaderTest {
    @get:Rule val folder = TemporaryFolder()

    private val source = SourceKey.parseOrThrow("custom_mihon_2499283573021220255")
    private val id = SeriesId(source, "/manga/a1b2:c3?lang=en")

    @Test fun `a chapter's mark says its number once, from the chapter's own name`() {
        assertEquals(ViewerStripMark("10", "S · Chapter 10"), seriesChapterMark("S", "Chapter 10", 10f))
        assertEquals(ViewerStripMark("0", "S · Chapter 0, Prologue"), seriesChapterMark("S", "Prologue", 0f))
        assertEquals(ViewerStripMark("1", "S · Chapter 1, Chapter 10"), seriesChapterMark("S", "Chapter 10", 1f))
        assertEquals(ViewerStripMark("10.5", "S · Ch. 10.5"), seriesChapterMark("S", "Ch. 10.5", 10.5f))
        // A number in the series' title does not count as the chapter saying its own.
        assertEquals(ViewerStripMark("8", "Kaiju No. 8 · Chapter 8, The Test"), seriesChapterMark("Kaiju No. 8", "The Test", 8f))
    }

    @Test
    fun chapterAndSeriesAddressesSurviveTheViewersSavedState() {
        val chapter = PostId(source, "/chapter/9f:8e/1?x=a:b")

        assertEquals(chapter, decodeViewerPostId(encodeViewerPostId(chapter)))
        assertEquals(id, seriesIdOf(seriesQueryHash(id)))
        assertNull(seriesIdOf("codex:saved"))
    }

    @Test
    fun aSessionHoldsEveryChapterOldestFirstAndResumesEachWhereItWasLeft() = runTest {
        // The source lists the newest first, so order 0 is chapter 3.
        val entries = listOf(
            StoredSeriesEntry(SeriesEntry("/c3", "Ch. 3"), sourceOrder = 0, firstSeenAtEpochMs = 0),
            StoredSeriesEntry(SeriesEntry("/c2", "Ch. 2", number = 2f), sourceOrder = 1, firstSeenAtEpochMs = 0, lastPage = 7, pageCount = 18),
            StoredSeriesEntry(SeriesEntry("/c1", "Ch. 1"), sourceOrder = 2, firstSeenAtEpochMs = 0, read = true, lastPage = 20, pageCount = 20),
        )
        val reader = SeriesReader(dependencies(StoredOnly(StoredSeries(Series(id, "Title")), entries), sources = { emptyList() }))

        val session = checkNotNull(reader.session(id, "/c2"))
        // Only chapters with a number carry one, for the strip to show.
        assertEquals(mapOf(PostId(source, "/c2") to ViewerStripMark("2", "Title · Ch. 2")), session.stripMarks)

        assertEquals(listOf("/c1", "/c2", "/c3"), session.posts.map { it.id.sourcePostId })
        assertEquals(1, session.context.startIndex)
        assertEquals(ViewerStreamSource.SERIES, session.context.streamSource)
        assertEquals("Title · Ch. 2", session.posts[1].title)
        assertTrue(session.posts.all { it.galleryLengthUnknown && it.media.isEmpty() })
        // Page 7 is index 6; a finished chapter starts over.
        assertEquals(6, session.initialMediaIndices[PostId(source, "/c2")])
        assertEquals(0, session.initialMediaIndices[PostId(source, "/c1")])
        assertNull(reader.session(id, "/missing"))
    }

    @Test
    fun aSessionKnowsItsReadingModeWithoutLoadingAnExtension() = runTest {
        val webtoon = StoredSeries(Series(id, "Title", genres = listOf("Long Strip")))
        fun reader(stored: StoredSeries) =
            SeriesReader(dependencies(StoredOnly(stored, listOf(chapter)), sources = { error("No extension may load") }))

        assertEquals(GalleryReadingMode.VERTICAL, reader(webtoon).session(id, "/c1")?.seriesReadingMode)
        assertEquals(
            GalleryReadingMode.LEFT_TO_RIGHT,
            reader(webtoon.copy(readingMode = GalleryReadingMode.LEFT_TO_RIGHT)).session(id, "/c1")?.seriesReadingMode,
        )
    }

    @Test
    fun aSessionShowsTheCoverItsLoadedSourceGivesAndNoneBeforeItLoads() = runTest {
        val stored = StoredOnly(StoredSeries(Series(id, "Title", coverUrl = "https://cover.test")), listOf(chapter))
        suspend fun cover(loaded: List<SeriesSource>) =
            SeriesReader(dependencies(stored, { error("No extension may load") }, loaded = loaded)).session(id, "/c1")?.posts?.single()?.preview?.url
        val tagging = object : SeriesSource by OnePage(source) {
            override fun coverAddress(series: Series) = series.coverUrl + "#tagged"
        }

        assertEquals("https://cover.test#tagged", cover(listOf(tagging)))
        assertNull(cover(emptyList()))
    }

    @Test
    fun aChapterThatFailsIsLoggedByKindWithoutTheExtensionsMessages() = runTest {
        val logged = mutableListOf<String>()
        Diagnostics.install { _, message, error -> logged += message + error?.message.orEmpty() }
        val failing = object : SeriesSource by OnePage(source) {
            override suspend fun pages(entry: SeriesEntry): List<ImageRef> = throw SourceAdapterException(
                SourceFailureReason.NETWORK, "Invalid page /chapter/private-title?sig=x", IllegalStateException("private-title"),
            )
        }
        val reader = SeriesReader(dependencies(StoredOnly(StoredSeries(Series(id, "Title")), listOf(chapter)), { listOf(failing) }))

        runCatching { reader.resolve(seriesQueryHash(id), PostId(source, "/c1")) }

        assertEquals(listOf("${source.id} chapter pages failed to load: SourceAdapterException(NETWORK) < IllegalStateException"), logged)
    }

    @Test
    fun aChapterIsRecordedOnlyOnceItsPagesShow() = runTest {
        val repository = StoredOnly(StoredSeries(Series(id, "Title")), listOf(chapter))
        val reader = SeriesReader(dependencies(repository, { listOf(OnePage(source)) }))
        val session = checkNotNull(reader.session(id, "/c1"))

        // The Viewer first reports the chapter while only its cover shows; resolving it reads nothing either.
        reader.recordProgress(session, session.posts.single(), mediaNumber = 1)
        val resolved = checkNotNull(reader.resolve(session.context.queryHash, session.posts.single().id))
        assertEquals(emptyList<String>(), repository.progress)

        reader.recordProgress(session, resolved, mediaNumber = 1)
        assertEquals(listOf("/c1 1/1"), repository.progress)
    }

    @Test
    fun resolvingAChapterAgainAsksItsSourceForFreshPages() = runTest {
        val pages = OnePage(source)
        val reader = SeriesReader(dependencies(StoredOnly(StoredSeries(Series(id, "Title")), listOf(chapter)), { listOf(pages) }))
        val chapterId = PostId(source, "/c1")

        val first = checkNotNull(reader.resolve(seriesQueryHash(id), chapterId))
        val again = checkNotNull(reader.resolve(seriesQueryHash(id), chapterId))

        assertEquals("https://pages.test/1.jpg", first.media.single().url)
        assertEquals("https://pages.test/2.jpg", again.media.single().url)
    }

    @Test
    fun aDownloadedChapterReadsFromItsFilesWithoutItsExtension() = runTest {
        val store = store()
        val stored = StoredOnly(StoredSeries(Series(id, "Title", coverUrl = "https://cover.test")), listOf(chapter))
        val dependencies = dependencies(stored, { error("No extension may load") }, store = store)
        download("/c1")

        val resolved = checkNotNull(SeriesReader(dependencies).resolve(seriesQueryHash(id), PostId(source, "/c1")))

        assertEquals(pagesOf("/c1").map { it.url }, resolved.media.map { File(checkNotNull(it.localPath)).readText() })
        // Their addresses name no type, yet the Viewer takes the files as images.
        assertTrue(resolved.media.all { isLikelyImageLocation(it.mime, checkNotNull(it.localPath)) })
        // Without its source loaded, no cover: the stored address can need the source's own client.
        assertNull(resolved.preview.url)
        // Anything that stores the chapter stores its source's pages, never the files.
        assertEquals(pagesOf("/c1"), store.withoutOfflineLocations(resolved).media)
    }

    @Test
    fun deleteAfterReadingWaitsForTheLastReaderToClose() = runTest {
        val reader = readerOverDownloadedChapters(deleteAfterReading = true)
        reader.opened()
        reader.opened()

        reader.closed()
        advanceUntilIdle()
        assertEquals(listOf("/c1", "/c2", "/c3"), onDevice())
        reader.closed()
        advanceUntilIdle()
        // Chapter 2 is unread, and the series was last opened at chapter 3.
        assertEquals(listOf("/c2", "/c3"), onDevice())
    }

    @Test
    fun aReaderOpenedBeforeDeleteAfterReadingRunsKeepsItsChapters() = runTest {
        val reader = readerOverDownloadedChapters(deleteAfterReading = true)
        reader.opened()
        reader.closed()
        reader.opened()
        advanceUntilIdle()
        assertEquals(listOf("/c1", "/c2", "/c3"), onDevice())
    }

    @Test
    fun withDeleteAfterReadingOffClosingKeepsReadChapters() = runTest {
        val reader = readerOverDownloadedChapters(deleteAfterReading = false)
        reader.opened()
        reader.closed()
        advanceUntilIdle()
        assertEquals(listOf("/c1", "/c2", "/c3"), onDevice())
    }

    /** Chapters 1 to 3 on the device, of which 1 and 3 are read, in a series last opened at 3. */
    private suspend fun TestScope.readerOverDownloadedChapters(deleteAfterReading: Boolean): SeriesReader {
        val entries = (3 downTo 1).mapIndexed { order, number ->
            StoredSeriesEntry(SeriesEntry("/c$number", "Ch. $number"), sourceOrder = order, firstSeenAtEpochMs = 0, read = number != 2)
        }
        listOf("/c1", "/c2", "/c3").forEach { download(it) }
        val stored = StoredSeries(Series(id, "Title"), lastOpenedEntry = "/c3")
        return SeriesReader(dependencies(StoredOnly(stored, entries), { error("unused") }, deleteAfterReading))
    }

    private suspend fun TestScope.onDevice() = store().run {
        load()
        snapshot.value.postsByOwner[seriesOfflineOwner(id)].orEmpty().map { it.sourcePostId }.sorted()
    }

    private fun TestScope.store() = OfflineMediaStore(folder.root.resolve("downloads"), StandardTestDispatcher(testScheduler))

    private fun pagesOf(address: String) = List(2) { ImageRef(url = "https://pages.test$address/$it", localPath = null, mime = null) }

    /** Puts the two pages of the chapter at [address] on the device, each file holding its page's address. */
    private suspend fun TestScope.download(address: String) {
        val pages = pagesOf(address)
        val post = chapterPost(Series(id, "Title"), SeriesEntry(address, address), null, pages)
        store().save(post, pages, seriesOfflineOwner(id)) { page, file ->
            file.writeText(checkNotNull(page.url))
            "image/png"
        }
    }

    /** The reader's dependencies over [repository], with downloads in this test's folder that never fetch. */
    private fun TestScope.dependencies(
        repository: SeriesRepository,
        sources: suspend () -> List<SeriesSource>,
        deleteAfterReading: Boolean = false,
        store: OfflineMediaStore = store(),
        loaded: List<SeriesSource> = emptyList(),
    ): SeriesDependencies {
        val downloads = SeriesDownloads(
            store = store,
            queueFile = folder.root.resolve("queue.json"),
            repository = repository,
            sources = sources,
            fetch = { _, _ -> error("Nothing downloads here") },
            scheduler = object : DownloadScheduler {
                override suspend fun schedule(automatic: Boolean) = Unit

                override suspend fun ensureScheduled(automatic: Boolean) = Unit
            },
            deleteAfterReading = { deleteAfterReading },
            files = AtomicJsonFileStore(StandardTestDispatcher(testScheduler)),
        )
        return SeriesDependencies(repository, sources, NoExtensions, downloads, scope = this, loadedSources = { loaded })
    }

    private val chapter = StoredSeriesEntry(SeriesEntry("/c1", "Ch. 1"), sourceOrder = 0, firstSeenAtEpochMs = 0)
}

/** A source whose every chapter has one page, at an address that changes each time it is asked for. */
private class OnePage(override val key: SourceKey) : SeriesSource {
    private var asked = 0
    override val name = "Fake"
    override val language = "en"
    override suspend fun pages(entry: SeriesEntry) = listOf(ImageRef(url = "https://pages.test/${++asked}.jpg", localPath = null, mime = null))
    override suspend fun popular(page: Int): SeriesPage = error("unused")
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage = error("unused")
    override suspend fun details(series: Series): Series = series
    override suspend fun entries(series: Series): List<SeriesEntry> = emptyList()
}

/** A repository that only answers what is stored; the reader's session needs nothing else. */
private class StoredOnly(private val stored: StoredSeries, private val entries: List<StoredSeriesEntry>) : SeriesRepository {
    val progress = mutableListOf<String>()
    override suspend fun series(id: SeriesId) = stored.takeIf { it.series.id == id }
    override suspend fun entries(id: SeriesId) = entries
    override fun observeFollowed(): Flow<List<FollowedSeries>> = emptyFlow()
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = emptyFlow()
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = emptyFlow()
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
    override suspend fun setAllRead(id: SeriesId, read: Boolean) = EntryProgressRestore()
    override suspend fun setEntriesRead(id: SeriesId, entryAddresses: List<String>, read: Boolean) = EntryProgressRestore()
    override suspend fun restoreProgress(id: SeriesId, restore: EntryProgressRestore) = Unit
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, page: Int, pageCount: Int?) {
        progress += "$entryAddress $page/$pageCount"
    }
}

/** The reader never asks about extensions. */
private object NoExtensions : SeriesExtensions {
    override val revision = MutableStateFlow(0)

    override suspend fun installed(): List<InstalledExtension> = emptyList()

    override suspend fun setOn(id: String, signer: String, on: Boolean) = installed()

    override suspend fun setLanguage(id: String, language: String, on: Boolean) = installed()

    override suspend fun settings(id: String) = emptyList<ExtensionSourceSettings>()

    override suspend fun change(id: String, sourceId: String, setting: ExtensionSetting) = settings(id)
}
