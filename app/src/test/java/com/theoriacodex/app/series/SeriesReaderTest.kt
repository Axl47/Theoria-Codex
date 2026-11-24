package com.theoriacodex.app.series

import com.theoriacodex.app.media.isLikelyImageLocation
import com.theoriacodex.app.viewer.ViewerPlaybackProgress
import com.theoriacodex.app.viewer.ViewerSeries
import com.theoriacodex.app.viewer.ViewerStripMark
import com.theoriacodex.app.viewer.decodeViewerPostId
import com.theoriacodex.app.viewer.encodeViewerPostId
import com.theoriacodex.app.viewer.requiresLazyMediaResolution
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.chapterPost
import com.theoriacodex.data.repository.seriesOfflineOwner
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesCovers
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesSource
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SeriesReaderTest {
    /** A test that listens to diagnostics leaves no listener behind for the tests after it. */
    @After
    fun stopListeningToDiagnostics() = Diagnostics.install { _, _, _ -> }

    @get:Rule val folder = TemporaryFolder()

    private val source = SourceKey.parseOrThrow("custom_mihon_2499283573021220255")
    private val id = SeriesId(source, "/manga/a1b2:c3?lang=en")
    private val anime = SeriesId(SourceKey.parseOrThrow("custom_aniyomi_7"), "/anime/1")
    private val stream = MediaStream("https://video.test/1.m3u8", "1080p")

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
            StoredSeriesEntry(SeriesEntry("/c2", "Ch. 2", number = 2f), sourceOrder = 1, firstSeenAtEpochMs = 0, position = 7, length = 18),
            StoredSeriesEntry(SeriesEntry("/c1", "Ch. 1"), sourceOrder = 2, firstSeenAtEpochMs = 0, read = true, position = 20, length = 20),
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

        assertEquals(ViewerSeries.Reading(GalleryReadingMode.VERTICAL), reader(webtoon).session(id, "/c1")?.series)
        assertEquals(
            ViewerSeries.Reading(GalleryReadingMode.LEFT_TO_RIGHT),
            reader(webtoon.copy(readingMode = GalleryReadingMode.LEFT_TO_RIGHT)).session(id, "/c1")?.series,
        )
    }

    @Test
    fun aSessionShowsTheLoadableCoverWithoutLoadingAnExtension() = runTest {
        val stored = StoredOnly(StoredSeries(Series(id, "Title", coverUrl = "https://cover.test")), listOf(chapter))
        val reader = SeriesReader(dependencies(stored, { error("No extension may load") }))

        assertEquals("https://cover.test#loadable", reader.session(id, "/c1")?.posts?.single()?.preview?.url)
    }

    @Test
    fun anEntryThatFailsIsLoggedByKindWithoutTheExtensionsMessages() = runTest {
        val logged = mutableListOf<String>()
        Diagnostics.install { _, message, error -> logged += message + error?.message.orEmpty() }
        val failure = SourceAdapterException(
            SourceFailureReason.NETWORK, "Invalid page /chapter/private-title?sig=x", IllegalStateException("private-title"),
        )
        listOf(id, anime).forEach { series ->
            val failing = FakeSeriesSource(series.source) { throw failure }
            val reader = SeriesReader(dependencies(StoredOnly(StoredSeries(Series(series, "Title")), listOf(chapter)), { listOf(failing) }))

            runCatching { reader.resolve(seriesQueryHash(series), PostId(series.source, "/c1")) }
        }

        val kinds = "SourceAdapterException(NETWORK) < IllegalStateException"
        assertEquals(
            listOf("${source.id} chapter pages failed to load: $kinds", "${anime.source.id} episode streams failed to load: $kinds"),
            logged,
        )
    }

    @Test
    fun aChapterIsRecordedOnlyOnceItsPagesShow() = runTest {
        val repository = StoredOnly(StoredSeries(Series(id, "Title")), listOf(chapter))
        val reader = SeriesReader(dependencies(repository, { listOf(onePage(source)) }))
        val session = checkNotNull(reader.session(id, "/c1"))

        // The Viewer first reports the chapter while only its cover shows; resolving it reads nothing either.
        reader.recordPageProgress(session, session.posts.single(), 1)
        val resolved = checkNotNull(reader.resolve(session.context.queryHash, session.posts.single().id))
        assertEquals(emptyList<String>(), repository.progress)

        reader.recordPageProgress(session, resolved, 1)
        assertEquals(listOf("/c1 1/1"), repository.progress)
        // A chapter has no playback to record.
        reader.recordPlaybackProgress(session, resolved, ViewerPlaybackProgress(positionMs = 5_000, durationMs = 10_000))
        assertEquals(listOf("/c1 1/1"), repository.progress)
    }

    @Test
    fun anEpisodeResolvesToItsStreamsAndRecordsItsPlaybackPosition() = runTest {
        val repository = StoredOnly(StoredSeries(Series(anime, "Title")), listOf(chapter))
        val reader = SeriesReader(dependencies(repository, { listOf(FakeSeriesSource(anime.source) { EntryMedia.Streams(listOf(stream)) }) }))
        val session = checkNotNull(reader.session(anime, "/c1"))

        val episode = checkNotNull(reader.resolve(session.context.queryHash, PostId(anime.source, "/c1")))
        assertEquals(listOf(stream), episode.media.single().streams)

        // A duration not known yet records only the entry opened last, as a chapter's pages still loading do.
        reader.recordPlaybackProgress(session, episode, ViewerPlaybackProgress(positionMs = 0, durationMs = 0))
        reader.recordPlaybackProgress(session, episode, ViewerPlaybackProgress(positionMs = 61_000, durationMs = 1_440_000))
        assertEquals(listOf("/c1 0/null", "/c1 61000/1440000"), repository.progress)
    }

    @Test
    fun anAnimeSessionStartsEachEpisodeWhereItWasLeftAndNeverAtAPage() = runTest {
        // The source lists the newest first; episode 1 is watched, episode 2 was left a minute in.
        val entries = listOf(
            episode(3, 0),
            episode(2, 1, position = 61_000, length = 1_440_000),
            episode(1, 2, position = 1_400_000, length = 1_440_000, read = true),
        )
        val reader = SeriesReader(dependencies(StoredOnly(StoredSeries(Series(anime, "Title")), entries), { error("No extension may load") }))

        val session = checkNotNull(reader.session(anime, "/e2", pageIndex = 4))

        assertEquals(listOf("/e1", "/e2", "/e3"), session.posts.map { it.id.sourcePostId })
        assertEquals(1, session.context.startIndex)
        val starts = listOf("/e1" to 0L, "/e2" to 61_000L, "/e3" to 0L).associate { (address, ms) -> PostId(anime.source, address) to ms }
        assertEquals(ViewerSeries.Watching(starts, seriesTitle = "Title"), session.series)
        assertEquals(emptyMap<PostId, Int>(), session.initialMediaIndices)
        // Strip marks number chapters; an episode session has none.
        assertEquals(emptyMap<PostId, ViewerStripMark>(), session.stripMarks)
        // Each is one video the Viewer looks up when it gets there, never a gallery to page.
        assertTrue(session.posts.all { it.mediaCount == 1 && it.media.isEmpty() && requiresLazyMediaResolution(it) })
    }

    @Test
    fun anEpisodeIsLookedUpOnceAndThenNeedsNoLookupOfItsOwn() = runTest {
        val episodes = FakeSeriesSource(anime.source) { EntryMedia.Streams(listOf(stream, stream.copy(url = "https://video.test/2.m3u8", label = "720p"))) }
        val reader = SeriesReader(dependencies(StoredOnly(StoredSeries(Series(anime, "Title")), listOf(episode(1, 0))), { listOf(episodes) }))

        val opened = checkNotNull(reader.resolve(seriesQueryHash(anime), PostId(anime.source, "/e1")))

        assertEquals(1, episodes.asked)
        assertEquals(listOf("1080p", "720p"), opened.media.single().streams.map { it.label })
        // An expired link comes back through the Viewer's media recovery, not a lookup on its own.
        assertFalse(requiresLazyMediaResolution(opened))
    }

    @Test
    fun mediaOfTheWrongShapeOrWithNothingToPlayIsAFailureTheViewerShows() = runTest {
        val page = ImageRef(url = "https://pages.test/1.jpg", localPath = null, mime = null)
        val cases = listOf(
            Triple(anime, EntryMedia.Pages(listOf(page)), "This extension served the wrong kind of media"),
            Triple(id, EntryMedia.Streams(listOf(stream)), "This extension served the wrong kind of media"),
            Triple(anime, EntryMedia.Streams(listOf(stream.copy(url = " "))), "No stream found for this episode"),
        )
        cases.forEach { (series, media, message) ->
            val stored = StoredOnly(StoredSeries(Series(series, "Title")), listOf(chapter))
            val reader = SeriesReader(dependencies(stored, { listOf(FakeSeriesSource(series.source) { media }) }))

            val failure = runCatching { reader.resolve(seriesQueryHash(series), PostId(series.source, "/c1")) }.exceptionOrNull()

            assertEquals(message, failure?.message)
        }
    }

    @Test
    fun anEpisodeRecordsOnlyPlaybackAndResumesWhereItStoppedUntilItsEnd() = runTest {
        val repository = StoredOnly(StoredSeries(Series(anime, "Title")), listOf(episode(1, 0)))
        val reader = SeriesReader(dependencies(repository, { listOf(FakeSeriesSource(anime.source) { EntryMedia.Streams(listOf(stream)) }) }))
        val session = checkNotNull(reader.session(anime, "/e1"))
        val opened = checkNotNull(reader.resolve(session.context.queryHash, session.posts.single().id))
        suspend fun startsAt() = (reader.session(anime, "/e1")?.series as ViewerSeries.Watching).startPositionsMs.values.single()

        // Its one media item showing is not page 1 of 1, which would mark it watched the moment it opens: it makes the
        // episode the one Continue opens, with no length, so it keeps where it was left.
        reader.recordPageProgress(session, opened, 1)
        assertEquals(listOf("/e1 0/null"), repository.progress)
        assertFalse(repository.entries(anime).single().read)

        reader.recordPlaybackProgress(session, opened, ViewerPlaybackProgress(positionMs = 61_000, durationMs = 1_440_000))
        assertEquals(61_000L, startsAt())
        assertFalse(repository.entries(anime).single().read)
        // 85% is watched, yet it resumes there; only its last 2 minutes start it over.
        reader.recordPlaybackProgress(session, opened, ViewerPlaybackProgress(positionMs = 1_224_000, durationMs = 1_440_000))
        assertTrue(repository.entries(anime).single().read)
        assertEquals(1_224_000L, startsAt())
        reader.recordPlaybackProgress(session, opened, ViewerPlaybackProgress(positionMs = 1_420_000, durationMs = 1_440_000))
        assertEquals(0L, startsAt())
    }

    @Test
    fun resolvingAChapterAgainAsksItsSourceForFreshPages() = runTest {
        val pages = onePage(source)
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
        assertEquals("https://cover.test#loadable", resolved.preview.url)
        // Anything that stores the chapter stores its source's pages, never the files.
        assertEquals(pagesOf("/c1"), store.withoutOfflineLocations(resolved).media)
    }

    @Test
    fun aSessionPicturesDownloadedChaptersByTheirFirstPageWithoutAnExtension() = runTest {
        val entries = listOf(chapter, StoredSeriesEntry(SeriesEntry("/c2", "Ch. 2", number = 2f), sourceOrder = 1, firstSeenAtEpochMs = 0))
        download("/c1")
        val reader = SeriesReader(dependencies(StoredOnly(StoredSeries(Series(id, "Title")), entries), { error("No extension may load") }))

        val session = checkNotNull(reader.session(id, "/c2"))

        val firstPage = checkNotNull(session.stripMarks[PostId(source, "/c1")]?.image?.localPath)
        assertEquals(pagesOf("/c1").first().url, File(firstPage).readText())
        // The undownloaded chapter keeps only its number; neither chapter's pages are read until the Viewer asks.
        assertEquals(ViewerStripMark("2", "Title · Ch. 2"), session.stripMarks[PostId(source, "/c2")])
        assertTrue(session.posts.all { it.media.isEmpty() })
    }

    @Test
    fun anOpenReadersStripPicturesAChapterOnceItDownloads() = runTest {
        val store = store()
        val reader = SeriesReader(dependencies(StoredOnly(StoredSeries(Series(id, "Title")), listOf(chapter)), { error("unused") }, store = store))
        val session = checkNotNull(reader.session(id, "/c1"))
        val seen = mutableListOf<Map<PostId, ViewerStripMark>>()
        // Not backgroundScope: advanceUntilIdle stops once only background work remains.
        val collector = launch { reader.stripMarks(session).collect { seen += it } }
        advanceUntilIdle()
        assertEquals(listOf(emptyMap<PostId, ViewerStripMark>()), seen)

        download("/c1", store)
        advanceUntilIdle()

        val firstPage = checkNotNull(seen.last()[PostId(source, "/c1")]?.image?.localPath)
        assertEquals(pagesOf("/c1").first().url, File(firstPage).readText())
        collector.cancel()
    }

    @Test
    fun chaptersThatOpenOnTheSamePageArePicturedByTheirSecond() = runTest {
        val store = store()
        download("/c1", store)
        val twoPages = object : SeriesSource by onePage(source) {
            override suspend fun media(entry: SeriesEntry): EntryMedia = EntryMedia.Pages(pagesOf(entry.address))
        }
        val entries = listOf(chapter, StoredSeriesEntry(SeriesEntry("/c2", "Ch. 2"), sourceOrder = 1, firstSeenAtEpochMs = 0))
        // Every chapter opens on the same credit page.
        val repository = StoredOnly(StoredSeries(Series(id, "Title")), entries)
        val credits = ChapterPictures(repository) { _, page -> address(page).let { if (it.endsWith("/0")) 1L else it.hashCode().toLong() } }
        val reader = SeriesReader(dependencies(repository, { listOf(twoPages) }, store = store, pictures = credits))
        val session = checkNotNull(reader.session(id, "/c2"))
        val seen = mutableListOf<Map<PostId, ViewerStripMark>>()
        val collector = launch { reader.stripMarks(session).collect { seen += it } }
        fun pictured() = seen.last().mapValues { (_, mark) -> mark.image?.let(::address) to mark.page }
        advanceUntilIdle()
        // One chapter has nothing to match yet.
        assertEquals(mapOf(PostId(source, "/c1") to (pagesOf("/c1")[0].url to 0)), pictured())

        // A loaded chapter is compared once it is being read, when the Viewer has fetched its first page, not as it loads.
        val loaded = checkNotNull(reader.resolve(session.context.queryHash, PostId(source, "/c2")))
        advanceUntilIdle()
        assertEquals(mapOf(PostId(source, "/c1") to (pagesOf("/c1")[0].url to 0)), pictured())
        reader.recordPageProgress(session, loaded, 1)
        advanceUntilIdle()

        // The downloaded chapter's second page is its file; the loaded one's, its own pages, which the Viewer holds.
        assertEquals(mapOf(PostId(source, "/c1") to (pagesOf("/c1")[1].url to 1), PostId(source, "/c2") to (null to 1)), pictured())
        collector.cancel()

        // The series keeps it: a Viewer in a new process, which has hashed nothing yet, opens on second pages.
        val restarted = checkNotNull(SeriesReader(dependencies(repository, { listOf(twoPages) }, store = store)).session(id, "/c2"))
        val opening = restarted.stripMarks.mapValues { (_, mark) -> mark.image?.let(::address) to mark.page }
        assertEquals(mapOf(PostId(source, "/c1") to (pagesOf("/c1")[1].url to 1), PostId(source, "/c2") to (null to 1)), opening)
    }

    /** A page's address, read from its file when it is downloaded. */
    private fun address(page: ImageRef) = page.url ?: File(checkNotNull(page.localPath)).readText()

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

    private suspend fun TestScope.onDevice() =
        store().load().postsByOwner[seriesOfflineOwner(id)].orEmpty().map { it.sourcePostId }.sorted()

    private fun TestScope.store() = OfflineMediaStore(folder.root.resolve("downloads"), StandardTestDispatcher(testScheduler))

    private fun pagesOf(address: String) = List(2) { ImageRef(url = "https://pages.test$address/$it", localPath = null, mime = null) }

    /** Puts the two pages of the chapter at [address] on the device, each file holding its page's address. */
    private suspend fun TestScope.download(address: String, store: OfflineMediaStore = store()) {
        val pages = pagesOf(address)
        val post = chapterPost(Series(id, "Title"), SeriesEntry(address, address), null, pages)
        store.save(post, pages, seriesOfflineOwner(id)) { page, file ->
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
        pictures: ChapterPictures = ChapterPictures(repository),
    ) = seriesDependencies(folder.root, repository, sources, deleteAfterReading, store, pictures)

    /** Every page of every chapter at an address that changes each time the source is asked. */
    private fun onePage(key: SourceKey) =
        FakeSeriesSource(key) { asked -> EntryMedia.Pages(listOf(ImageRef(url = "https://pages.test/$asked.jpg", localPath = null, mime = null))) }

    private fun episode(number: Int, order: Int, position: Long = 0, length: Long? = null, read: Boolean = false) = StoredSeriesEntry(
        SeriesEntry("/e$number", "Ep. $number"), sourceOrder = order, firstSeenAtEpochMs = 0, read = read, position = position, length = length,
    )

    private val chapter = StoredSeriesEntry(SeriesEntry("/c1", "Ch. 1"), sourceOrder = 0, firstSeenAtEpochMs = 0)
}
