package com.theoriacodex.app.series

import com.theoriacodex.app.di.SeriesDependencies
import com.theoriacodex.app.viewer.decodeViewerPostId
import com.theoriacodex.app.viewer.encodeViewerPostId
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.data.repository.DownloadScheduler
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesDownloadStore
import com.theoriacodex.data.repository.SeriesDownloads
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
            StoredSeriesEntry(SeriesEntry("/c2", "Ch. 2"), sourceOrder = 1, firstSeenAtEpochMs = 0, lastPage = 7, pageCount = 18),
            StoredSeriesEntry(SeriesEntry("/c1", "Ch. 1"), sourceOrder = 2, firstSeenAtEpochMs = 0, read = true, lastPage = 20, pageCount = 20),
        )
        val reader = SeriesReader(dependencies(StoredOnly(StoredSeries(Series(id, "Title")), entries), sources = { emptyList() }))

        val session = checkNotNull(reader.session(id, "/c2"))

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
        val dependencies = dependencies(StoredOnly(StoredSeries(Series(id, "Title", coverUrl = "https://cover.test")), listOf(chapter)), { error("No extension may load") })
        download("/c1")

        val resolved = checkNotNull(SeriesReader(dependencies).resolve(seriesQueryHash(id), PostId(source, "/c1")))

        assertEquals(listOf("0001.png", "0002.png"), resolved.media.map { File(checkNotNull(it.localPath)).name })
        assertTrue(resolved.media.all { it.url == null })
        assertEquals("https://cover.test", resolved.preview.url)
    }

    @Test
    fun deleteAfterReadingDeletesTheReadChaptersShownOnceTheViewerCloses() = runTest {
        val reader = readerOverDownloadedChapters(deleteAfterReading = true)
        val session = checkNotNull(reader.session(id, "/c1"))
        val other = checkNotNull(reader.session(id, "/c3"))

        reader.show(other, "/c3")
        listOf("/c1", "/c2", "/c3").forEach { reader.show(session, it) }
        // Moving on deletes nothing.
        assertEquals(listOf("/c1", "/c2", "/c3"), onDevice())

        reader.closed(session)
        advanceUntilIdle()
        // Chapter 2 is unread, and the other session still shows chapter 3.
        assertEquals(listOf("/c2", "/c3"), onDevice())
        reader.closed(other)
        advanceUntilIdle()
        assertEquals(listOf("/c2"), onDevice())
    }

    @Test
    fun withDeleteAfterReadingOffLeavingAReadChapterKeepsIt() = runTest {
        val reader = readerOverDownloadedChapters(deleteAfterReading = false)
        val session = checkNotNull(reader.session(id, "/c1"))

        reader.show(session, "/c1")
        reader.show(session, "/c2")
        reader.closed(session)
        advanceUntilIdle()
        assertEquals(listOf("/c1", "/c2", "/c3"), onDevice())
    }

    /** Chapters 1 to 3 on the device, of which 1 and 3 are read. */
    private suspend fun TestScope.readerOverDownloadedChapters(deleteAfterReading: Boolean): SeriesReader {
        val entries = (3 downTo 1).mapIndexed { order, number ->
            StoredSeriesEntry(SeriesEntry("/c$number", "Ch. $number"), sourceOrder = order, firstSeenAtEpochMs = 0, read = number != 2)
        }
        listOf("/c1", "/c2", "/c3").forEach { download(it) }
        return SeriesReader(dependencies(StoredOnly(StoredSeries(Series(id, "Title")), entries), { error("unused") }, deleteAfterReading))
    }

    /** What the Viewer reports once the chapter at [address] shows its first page. */
    private suspend fun SeriesReader.show(session: ViewerSession, address: String) {
        val post = session.posts.single { it.id.sourcePostId == address }
        recordProgress(session, post.copy(media = listOf(ImageRef(url = null, localPath = "/page", mime = null))), mediaNumber = 1)
    }

    private suspend fun TestScope.onDevice() = store().downloaded(id).sorted()

    private fun TestScope.store() = SeriesDownloadStore(folder.root.resolve("downloads"), StandardTestDispatcher(testScheduler))

    /** Puts two solid pages of the chapter at [address] on the device. */
    private suspend fun TestScope.download(address: String) = store().write(id, address) { staging ->
        List(2) { File(staging, SeriesDownloadStore.pageFileName(it, "png")).apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)) } }
    }

    /** The reader's dependencies over [repository], with downloads in this test's folder that never fetch. */
    private fun TestScope.dependencies(
        repository: SeriesRepository,
        sources: suspend () -> List<SeriesSource>,
        deleteAfterReading: Boolean = false,
    ): SeriesDependencies {
        val downloads = SeriesDownloads(
            store = store(),
            queueFile = folder.root.resolve("queue.json"),
            repository = repository,
            sources = sources,
            fetch = { _, _ -> error("Nothing downloads here") },
            scheduler = object : DownloadScheduler {
                override suspend fun schedule(automatic: Boolean) = Unit

                override suspend fun ensureScheduled(automatic: Boolean) = Unit
            },
            files = AtomicJsonFileStore(StandardTestDispatcher(testScheduler)),
        )
        return SeriesDependencies(repository, sources, NoExtensions, downloads, { deleteAfterReading }, scope = this)
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
