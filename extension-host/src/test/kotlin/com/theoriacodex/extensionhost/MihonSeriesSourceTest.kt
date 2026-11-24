package com.theoriacodex.extensionhost

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesStatus
import com.theoriacodex.domain.series.extensionSeriesStatus
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MihonSeriesSourceTest {
    private val source = FakeSource()
    private val series = MihonSeriesSource(source, HostServices(ExtensionImages()))

    @Test
    fun `the source is named by its id, name and language`() {
        assertEquals("custom_mihon_7", series.key.id)
        assertEquals("Fake", series.name)
        assertEquals("en", series.language)
    }

    @Test
    fun `popular, latest and search each ask the source for their page`() = runTest {
        source.listing = MangasPage(emptyList(), hasNextPage = true)

        assertTrue(series.popular(1).hasNextPage)
        assertTrue(series.supportsLatest)
        series.latest(2)
        series.search("one punch", 3)

        assertEquals(listOf("popular 1", "latest 2", "search 3 'one punch'"), source.calls)
    }

    @Test
    fun `a listed series carries its address, cover, author and genres`() = runTest {
        source.listing = MangasPage(
            listOf(manga("/series/a/b", "Series", cover = "https://cdn.test/c.jpg").also { it.author = "Author"; it.genre = "Action, Long Strip" }),
            hasNextPage = false,
        )

        val listed = series.popular(1).series.single()

        assertEquals(SeriesId(series.key, "/series/a/b"), listed.id)
        assertEquals("Series", listed.title)
        assertEquals("https://cdn.test/c.jpg", listed.coverUrl)
        assertEquals("Author", listed.author)
        assertEquals(listOf("Action", "Long Strip"), listed.genres)
        assertEquals("en", listed.language)
        assertFalse(series.popular(1).hasNextPage)
    }

    @Test
    fun `details carry the description and Mihon's status, and details without them keep the earlier ones`() = runTest {
        source.listing = MangasPage(listOf(manga("/s", "Series")), hasNextPage = false)
        val listed = series.popular(1).series.single()
        assertNull(listed.description)
        assertEquals(SeriesStatus.UNKNOWN, listed.status)

        source.details = SManga.create().also { it.description = " A synopsis. "; it.status = SManga.ON_HIATUS }
        val detailed = series.details(listed)
        assertEquals("A synopsis.", detailed.description)
        assertEquals(SeriesStatus.ON_HIATUS, detailed.status)

        source.details = SManga.create()
        assertEquals(detailed, series.details(listed))
    }

    @Test
    fun `each status number Mihon and Aniyomi use has its status, and any other is unknown`() {
        val expected = listOf(
            SeriesStatus.UNKNOWN, SeriesStatus.ONGOING, SeriesStatus.COMPLETED, SeriesStatus.LICENSED,
            SeriesStatus.PUBLISHING_FINISHED, SeriesStatus.CANCELLED, SeriesStatus.ON_HIATUS, SeriesStatus.UPCOMING, SeriesStatus.UNKNOWN,
        )
        assertEquals(expected, (0..8).map(::extensionSeriesStatus))
    }

    @Test
    fun `details that name only what they add keep the listed title and cover`() = runTest {
        source.listing = MangasPage(listOf(manga("/s", "Series", cover = "https://cdn.test/c.jpg")), hasNextPage = false)
        source.details = SManga.create().also { it.genre = "Webtoon" }
        val listed = series.popular(1).series.single()

        val detailed = series.details(listed)

        assertEquals("Series", detailed.title)
        assertEquals("https://cdn.test/c.jpg", detailed.coverUrl)
        assertEquals(listOf("Webtoon"), detailed.genres)
        assertEquals(listOf("/s details"), source.updates)
    }

    @Test
    fun `the source gets its own listed series back, and a stored one rebuilt from its address`() = runTest {
        val listedManga = manga("/s", "Series")
        source.listing = MangasPage(listOf(listedManga), hasNextPage = false)
        val listed = series.popular(1).series.single()

        series.entries(listed)
        assertSame(listedManga, source.asked.last())

        series.entries(Series(SeriesId(series.key, "/stored"), "Stored"))
        assertEquals("/stored", source.asked.last().url)
        assertEquals("Stored", source.asked.last().title)
    }

    @Test
    fun `entries keep the source's order and what it says about each, reading a number left out from the name`() = runTest {
        source.chapters = listOf(
            chapter("Chapter 2", "/c/2").also { it.chapter_number = 2f; it.scanlator = "Group"; it.date_upload = 2_000L },
            chapter("Chapter 1", "/c/1"),
        )

        val entries = series.entries(Series(SeriesId(series.key, "/s"), "Series"))

        assertEquals(listOf("/s chapters"), source.updates)
        assertEquals(SeriesEntry("/c/2", "Chapter 2", number = 2f, translatorGroup = "Group", uploadedAtEpochMs = 2_000L), entries[0])
        assertEquals(SeriesEntry("/c/1", "Chapter 1", number = 1f), entries[1])
    }

    @Test
    fun `media is the chapter's pages, for a listed entry and for one stored in an earlier run`() = runTest {
        source.chapters = listOf(chapter("Chapter 1", "/c/1"))
        source.pages = listOf(Page(0, imageUrl = "https://cdn.test/1.jpg"), Page(1, imageUrl = "https://cdn.test/2.jpg"))
        val listed = series.entries(Series(SeriesId(series.key, "/s"), "Series")).single()

        assertEquals(SeriesKind.MANGA, series.kind)
        val media = series.media(listed)
        assertEquals(listOf("https://cdn.test/1.jpg", "https://cdn.test/2.jpg"), (media as EntryMedia.Pages).pages.map { it.url })
        assertSame(source.chapters.single(), source.pageListRequests.last())

        series.media(SeriesEntry("/c/9", "Chapter 9", number = 9f))
        assertEquals("/c/9", source.pageListRequests.last().url)
        assertEquals(9f, source.pageListRequests.last().chapter_number)
        assertNull(source.pageListRequests.last().scanlator)
    }

    @Test
    fun `failures map to the reasons built-in sources report`() = runTest {
        assertEquals(SourceFailureReason.RATE_LIMITED, failureOf(HttpException(429)))
        assertEquals(SourceFailureReason.NETWORK, failureOf(HttpException(403)))
        assertEquals(SourceFailureReason.NETWORK, failureOf(HttpException(500)))
        assertEquals(SourceFailureReason.NETWORK, failureOf(IOException("offline")))
        assertEquals(SourceFailureReason.UNKNOWN, failureOf(NoSuchMethodError("missing")))
        assertEquals(SourceFailureReason.PARSE, failureOf(IllegalStateException("bad html")))
    }

    @Test
    fun `an extension's own cancellation is its failure, and the caller's passes through`() = runTest {
        source.failure = CancellationException("Timed out inside the extension")
        assertEquals(SourceFailureReason.PARSE, assertFailsWith<SourceAdapterException> { series.popular(1) }.reason)

        var thrown: Throwable? = null
        launch {
            cancel()
            thrown = runCatching { series.popular(1) }.exceptionOrNull()
        }.join()
        assertTrue("$thrown", thrown is CancellationException)
    }

    @Test
    fun `webUrl resolves through HttpSource and falls back to full series addresses`() {
        val http = object : HttpSource() {
            override val name = "Web"
            override val baseUrl = "https://manga.example"
            override val lang = "en"
            override val supportsLatest = false
            override fun headersBuilder() = okhttp3.Headers.Builder()
            override fun popularMangaParse(response: okhttp3.Response) = MangasPage(emptyList(), false)
            override fun searchMangaParse(response: okhttp3.Response) = MangasPage(emptyList(), false)
            override fun mangaDetailsParse(response: okhttp3.Response) = SManga.create()
            override fun chapterListParse(response: okhttp3.Response) = emptyList<SChapter>()
            override fun pageListParse(response: okhttp3.Response) = emptyList<Page>()
            override fun imageUrlParse(response: okhttp3.Response) = ""
        }
        val webSource = MihonSeriesSource(http, HostServices(ExtensionImages()))
        assertEquals("https://manga.example/series/42", webSource.webUrl(Series(SeriesId(webSource.key, "/series/42"), "Title")))
        assertNull(series.webUrl(Series(SeriesId(series.key, "/relative"), "Title")))
        assertEquals("https://direct.example/s", series.webUrl(Series(SeriesId(series.key, "https://direct.example/s"), "Title")))
    }

    private suspend fun failureOf(error: Throwable): SourceFailureReason {
        source.failure = error
        return assertFailsWith<SourceAdapterException> { series.popular(1) }.reason
    }

    private fun manga(url: String, title: String, cover: String? = null): SManga = SManga.create().also {
        it.url = url
        it.title = title
        it.thumbnail_url = cover
    }

    private fun chapter(name: String, url: String): SChapter = SChapter.create().also {
        it.name = name
        it.url = url
    }

    private class FakeSource : CatalogueSource {
        override val id = 7L
        override val name = "Fake"
        override val lang = "en"
        override val supportsLatest = true

        var listing = MangasPage(emptyList(), hasNextPage = false)
        var chapters = emptyList<SChapter>()

        /** What the details page returns; the series it was asked about when null. */
        var details: SManga? = null
        var pages = emptyList<Page>()
        var failure: Throwable? = null

        val calls = mutableListOf<String>()
        val updates = mutableListOf<String>()
        val asked = mutableListOf<SManga>()
        val pageListRequests = mutableListOf<SChapter>()

        override suspend fun getPopularManga(page: Int): MangasPage = answer("popular $page")

        override suspend fun getLatestUpdates(page: Int): MangasPage = answer("latest $page")

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = answer("search $page '$query'")

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate {
            asked += manga
            updates += "${manga.url}${if (fetchDetails) " details" else ""}${if (fetchChapters) " chapters" else ""}"
            return SMangaUpdate(details ?: manga, this.chapters)
        }

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            pageListRequests += chapter
            return pages
        }

        private fun answer(call: String): MangasPage {
            calls += call
            failure?.let { throw it }
            return listing
        }
    }
}
