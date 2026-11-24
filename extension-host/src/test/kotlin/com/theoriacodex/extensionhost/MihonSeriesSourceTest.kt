package com.theoriacodex.extensionhost

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.MAX_MEMO_LENGTH
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    fun `details carry the description and Mihon's status, and details without them keep the earlier ones (ADR 0064)`() = runTest {
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
    fun `each status number Mihon and Aniyomi use has its status, and any other is unknown (ADR 0007)`() {
        val expected = listOf(
            SeriesStatus.UNKNOWN, SeriesStatus.ONGOING, SeriesStatus.COMPLETED, SeriesStatus.LICENSED,
            SeriesStatus.PUBLISHING_FINISHED, SeriesStatus.CANCELLED, SeriesStatus.ON_HIATUS, SeriesStatus.UPCOMING, SeriesStatus.UNKNOWN,
        )
        assertEquals(expected, (0..8).map(::extensionSeriesStatus))
    }

    @Test
    fun `details that name only what they add keep the listed title and cover (ADR 0064)`() = runTest {
        source.listing = MangasPage(listOf(manga("/s", "Series", cover = "https://cdn.test/c.jpg")), hasNextPage = false)
        source.details = SManga.create().also { it.title = " "; it.thumbnail_url = ""; it.genre = "Webtoon" }
        val listed = series.popular(1).series.single()

        val detailed = series.details(listed)

        assertEquals("Series", detailed.title)
        assertEquals("https://cdn.test/c.jpg", detailed.coverUrl)
        assertEquals(listOf("Webtoon"), detailed.genres)
        assertEquals(listOf("/s details"), source.updates)
    }

    @Test
    fun `the source gets its own listed series back, and a stored one rebuilt from its address (ADR 0064)`() = runTest {
        val listedManga = manga("/s", "Series")
        source.listing = MangasPage(listOf(listedManga), hasNextPage = false)
        val listed = series.popular(1).series.single()

        series.entries(listed, emptyList())
        assertSame(listedManga, source.asked.last())

        series.entries(Series(SeriesId(series.key, "/stored"), "Stored"), emptyList())
        assertEquals("/stored", source.asked.last().url)
        assertEquals("Stored", source.asked.last().title)
    }

    @Test
    fun `memos the source keeps reach the app and come back when a stored series or chapter is rebuilt (ADR 0007, 0064)`() = runTest {
        val slug = buildJsonObject { put("slug", "s-1") }
        source.listing = MangasPage(listOf(manga("/s", "Series").also { it.memo = slug }), hasNextPage = false)
        val oversized = buildJsonObject { put("blob", "x".repeat(MAX_MEMO_LENGTH)) }
        source.chapters = listOf(
            chapter("Chapter 1", "/c/1").also { it.memo = buildJsonObject { put("id", 1) } },
            chapter("Chapter 2", "/c/2").also { it.memo = oversized },
        )
        val listed = series.popular(1).series.single()
        assertEquals("""{"slug":"s-1"}""", listed.memo)
        assertEquals(listOf("""{"id":1}""", "{}"), series.entries(listed, emptyList()).entries.map { it.memo })

        val later = MihonSeriesSource(source, HostServices(ExtensionImages()))
        later.details(listed)
        assertEquals(slug, source.asked.last().memo)
        later.media(SeriesEntry("/c/1", "Chapter 1", memo = """{"id":1}"""))
        assertEquals(buildJsonObject { put("id", 1) }, source.pageListRequests.last().memo)
        later.media(SeriesEntry("/c/2", "Chapter 2", memo = "not json"))
        assertTrue(source.pageListRequests.last().memo.isEmpty())
        val rebuilt = source.pageListRequests.last()
        later.media(SeriesEntry("/c/2", "Chapter 2"))
        assertSame(rebuilt, source.pageListRequests.last())
    }

    @Test
    fun `a series listed without its memo takes the stored one, and details that give a memo replace it (ADR 0064)`() = runTest {
        source.listing = MangasPage(listOf(manga("/s", "Series")), hasNextPage = false)
        val listed = series.popular(1).series.single()
        assertNull(listed.memo)

        series.entries(listed.copy(memo = """{"slug":"s-1"}"""), emptyList())
        assertEquals(buildJsonObject { put("slug", "s-1") }, source.asked.last().memo)

        source.details = manga("/s", "Series").also { it.memo = buildJsonObject { put("slug", "s-2") } }
        assertEquals("""{"slug":"s-2"}""", series.details(listed).memo)

        source.details = null
        source.clearMemo = true
        assertEquals("""{"slug":"s-2"}""", series.details(listed).memo)
    }

    @Test
    fun `a listing is handed the stored chapters, memos included, and the series memo it leaves comes back (ADR 0007, 0064)`() = runTest {
        source.chapters = listOf(chapter("Chapter 2", "/c/2"), chapter("Chapter 1", "/c/1"))
        source.listingMemo = buildJsonObject { put("pages", 3) }
        val stored = Series(SeriesId(series.key, "/s"), "Series")

        val listed = series.entries(stored, listOf(SeriesEntry("/c/1", "Chapter 1", 1f, memo = """{"id":1}""")))

        val handed = source.handedChapters.single().single()
        assertEquals("/c/1", handed.url)
        assertEquals(buildJsonObject { put("id", 1) }, handed.memo)
        assertEquals("""{"pages":3}""", listed.seriesMemo)
        series.details(stored)
        assertEquals(buildJsonObject { put("pages", 3) }, source.asked.last().memo)

        source.listingMemo = JsonObject(emptyMap())
        assertEquals("{}", series.entries(stored, emptyList()).seriesMemo)
        assertTrue(source.asked.last().memo.isEmpty())
    }

    @Test
    fun `a chapter the source listed without a memo takes the stored one when it is handed back (ADR 0064)`() = runTest {
        source.chapters = listOf(chapter("Chapter 1", "/c/1"))
        val stored = Series(SeriesId(series.key, "/s"), "Series")
        series.entries(stored, emptyList())

        series.entries(stored, listOf(SeriesEntry("/c/1", "Chapter 1", 1f, memo = """{"id":1}""")))

        val handed = source.handedChapters.last().single()
        assertSame(source.chapters.single(), handed)
        assertEquals(buildJsonObject { put("id", 1) }, handed.memo)
    }

    @Test
    fun `a series memo the source clears in place while listing is cleared, not kept (ADR 0064)`() = runTest {
        source.clearListingMemoInPlace = true

        val listed = series.entries(Series(SeriesId(series.key, "/s"), "Series", memo = """{"pages":3}"""), emptyList())

        assertEquals("{}", listed.seriesMemo)
    }

    @Test
    fun `a source's filter list is asked like its other calls, so its failure is the source's (ADR 0060)`() = runTest {
        source.filterFailure = IllegalStateException("No filters")

        assertEquals(SourceFailureReason.PARSE, assertFailsWith<SourceAdapterException> { series.filters() }.reason)
    }

    @Test
    fun `a chapter listed twice is the first, for the app and for its pages (ADR 0064)`() = runTest {
        source.chapters = listOf(
            chapter("Chapter 1", "/c/1").also { it.memo = buildJsonObject { put("id", 1) } },
            chapter("Chapter 1 again", "/c/1").also { it.memo = buildJsonObject { put("id", 2) } },
        )
        val entry = series.entries(Series(SeriesId(series.key, "/s"), "Series"), emptyList()).entries.single()

        assertEquals("""{"id":1}""", entry.memo)
        series.media(entry)
        assertSame(source.chapters.first(), source.pageListRequests.last())
    }

    @Test
    fun `entries keep the source's order and what it says about each, reading a number left out from the name`() = runTest {
        source.chapters = listOf(
            chapter("Chapter 2", "/c/2").also { it.chapter_number = 2f; it.scanlator = "Group"; it.date_upload = 2_000L },
            chapter("Chapter 1", "/c/1"),
        )

        val entries = series.entries(Series(SeriesId(series.key, "/s"), "Series"), emptyList()).entries

        assertEquals(listOf("/s chapters"), source.updates)
        assertEquals(SeriesEntry("/c/2", "Chapter 2", number = 2f, translatorGroup = "Group", uploadedAtEpochMs = 2_000L), entries[0])
        assertEquals(SeriesEntry("/c/1", "Chapter 1", number = 1f), entries[1])
    }

    @Test
    fun `media is the chapter's pages, for a listed entry and for one stored in an earlier run`() = runTest {
        source.chapters = listOf(chapter("Chapter 1", "/c/1"))
        source.pages = listOf(Page(0, imageUrl = "https://cdn.test/1.jpg"), Page(1, imageUrl = "https://cdn.test/2.jpg"))
        val listed = series.entries(Series(SeriesId(series.key, "/s"), "Series"), emptyList()).entries.single()

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
    fun `failures map to the reasons built-in sources report (ADR 0060)`() = runTest {
        assertEquals(SourceFailureReason.RATE_LIMITED, failureOf(HttpException(429)))
        assertEquals(SourceFailureReason.NETWORK, failureOf(HttpException(403)))
        assertEquals(SourceFailureReason.NETWORK, failureOf(HttpException(500)))
        assertEquals(SourceFailureReason.NETWORK, failureOf(IOException("offline")))
        assertEquals(SourceFailureReason.UNKNOWN, failureOf(NoSuchMethodError("missing")))
        assertEquals(SourceFailureReason.PARSE, failureOf(IllegalStateException("bad html")))
    }

    @Test
    fun `an extension's own cancellation is its failure, and the caller's passes through (ADR 0060)`() = runTest {
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

        var details: SManga? = null
        var pages = emptyList<Page>()
        var failure: Throwable? = null

        var clearMemo = false

        var clearListingMemoInPlace = false

        var filterFailure: Throwable? = null

        override fun getFilterList(): FilterList = filterFailure?.let { throw it } ?: FilterList()

        var listingMemo: JsonObject? = null
        val handedChapters = mutableListOf<List<SChapter>>()

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
            if (fetchDetails && clearMemo) manga.memo = JsonObject(emptyMap())
            if (!fetchDetails && clearListingMemoInPlace) manga.memo = JsonObject(emptyMap())
            updates += "${manga.url}${if (fetchDetails) " details" else ""}${if (fetchChapters) " chapters" else ""}"
            if (fetchChapters) handedChapters += chapters
            val listingCopy = listingMemo?.takeIf { !fetchDetails }?.let { memo -> manga.copy().also { it.memo = memo } }
            return SMangaUpdate(listingCopy ?: details ?: manga, this.chapters)
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
