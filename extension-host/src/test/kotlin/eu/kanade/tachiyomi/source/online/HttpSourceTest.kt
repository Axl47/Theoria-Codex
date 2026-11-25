package eu.kanade.tachiyomi.source.online

import com.theoriacodex.extensionhost.answering
import com.theoriacodex.extensionhost.cannedResponse
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import com.theoriacodex.extensionhost.assertFailsWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uy.kohesive.injekt.Injekt

class HttpSourceTest {
    private val requested = CopyOnWriteArrayList<String>()
    private var status = 200

    @Before
    fun bindClient() {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                answering { request ->
                    requested += request.url.encodedPath
                    cannedResponse(request, body = request.url.encodedPath, code = status)
                },
            )
            .build()
        Injekt.bind(NetworkHelper::class.java, NetworkHelper(client) { "test-agent" })
    }

    @Test
    fun `popular, latest and search drive their request and parse pairs`() = runTest {
        val source = TestSource()

        val popular = source.getPopularManga(3)
        val latest = source.getLatestUpdates(4)
        val search = source.getSearchManga(5, "naruto", FilterList())

        assertEquals(listOf("/popular/3", "/latest/4", "/search/5/naruto"), requested)
        assertEquals(listOf("/popular/3", "/latest/4", "/search/5/naruto"), listOf(popular, latest, search).map { it.mangas.single().url })
    }

    @Test
    fun `getMangaUpdate fetches details and chapters when both are asked for`() = runTest {
        val update = TestSource().getMangaUpdate(manga("/m"), emptyList(), fetchDetails = true, fetchChapters = true)

        assertEquals(setOf("/details/m", "/chapters/m"), requested.toSet())
        assertEquals("/details/m", update.manga.title)
        assertTrue(update.manga.initialized)
        assertEquals(listOf("/chapters/m"), update.chapters.map { it.name })
    }

    @Test
    fun `getMangaUpdate keeps what the unset flag leaves out`() = runTest {
        val given = manga("/m")
        val kept = chapter("kept")

        val update = TestSource().getMangaUpdate(given, listOf(kept), fetchDetails = false, fetchChapters = false)

        assertEquals(emptyList<String>(), requested)
        assertEquals(given, update.manga)
        assertEquals(listOf(kept), update.chapters)
    }

    @Test
    fun `getPageList and getImageUrl drive their helper pairs`() = runTest {
        val source = TestSource()

        val pages = source.getPageList(chapter("c").also { it.url = "/c/1" })
        val imageUrl = source.getImageUrl(Page(0, url = "https://example.test/page/1"))

        assertEquals(listOf("/pages/c/1", "/page/1"), requested)
        assertEquals("/pages/c/1", pages.single().imageUrl)
        assertEquals("/page/1", imageUrl)
    }

    @Test
    fun `a non-2xx response surfaces as HttpException with its code`() = runTest {
        status = 503

        val failure = assertFailsWith<HttpException> { TestSource().getPopularManga(1) }

        assertEquals(503, failure.code)
    }

    @Test
    fun `id is stable for the same name, lang and versionId and differs by lang`() {
        assertEquals(TestSource().id, TestSource().id)
        assertNotEquals(TestSource().id, TestSource(lang = "fr").id)
    }

    @Test
    fun `a source's id is the one Mihon and Aniyomi compute from its name, language and version (ADR 0056, 0065)`() {
        val anime = object : AnimeHttpSource() {
            override val name = "MangaDex"
            override val lang = "en"
            override val baseUrl = "https://anime.test"
            override val supportsLatest = false
        }

        assertEquals(2_499_283_573_021_220_255L, TestSource(name = "MangaDex").id)
        assertEquals(2_499_283_573_021_220_255L, anime.id)
        assertEquals(490_041_738_643_955_318L, TestSource(name = "Alpha").id)
        assertEquals(490_041_738_643_955_318L, TestSource(name = "ALPHA").id)
        assertEquals(2_126_524_715_004_219_874L, TestSource(name = "MangaDex", versionId = 2).id)
        assertEquals(2_655_149_515_337_070_132L, TestSource(name = "MangaDex", lang = "pt-BR").id)
    }

    @Test
    fun `withoutDomain keeps a URL's path, query and fragment, and returns what is no URI unchanged (ADR 0065)`() {
        assertEquals("/series/1?lang=en#top", withoutDomain("https://example.test/series/1?lang=en#top"))
        assertEquals("http://[bad", withoutDomain("http://[bad"))
    }

    @Test
    fun `the extension API classes offer no RxJava method (ADR 0056)`() {
        val api = listOf(Source::class.java, HttpSource::class.java, AnimeSource::class.java, AnimeHttpSource::class.java, ParsedAnimeHttpSource::class.java)

        val rx = api.flatMap { type ->
            (type.methods + type.declaredMethods).filter { method -> (method.parameterTypes + method.returnType).any { it.name.startsWith("rx.") || it.name.startsWith("io.reactivex.") } }
                .map { "${type.simpleName}.${it.name}" }
        }

        assertTrue(api.all { it.methods.isNotEmpty() })
        assertEquals(emptyList<String>(), rx)
    }

    @Test
    fun `setUrlWithoutDomain strips the host`() {
        val source = TestSource()
        val manga = manga("")
        val chapter = chapter("")

        with(source) {
            manga.setUrlWithoutDomain("https://example.test/series/1?lang=en")
            chapter.setUrlWithoutDomain("https://example.test/series/1/c/2")
        }

        assertEquals("/series/1?lang=en", manga.url)
        assertEquals("/series/1/c/2", chapter.url)
    }

    private fun manga(url: String): SManga = SManga.create().also {
        it.url = url
        it.title = ""
    }

    private fun chapter(name: String): SChapter = SChapter.create().also {
        it.name = name
        it.url = ""
    }

    private class TestSource(override val lang: String = "en", override val name: String = "Test", override val versionId: Int = 1) : HttpSource() {
        override val baseUrl = "https://example.test"
        override val supportsLatest = true

        override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular/$page")

        override fun popularMangaParse(response: Response) = singleManga(response)

        override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest/$page")

        override fun latestUpdatesParse(response: Response) = singleManga(response)

        override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/search/$page/$query")

        override fun searchMangaParse(response: Response) = singleManga(response)

        override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/details${manga.url}")

        override fun mangaDetailsParse(response: Response): SManga = SManga.create().also {
            it.url = ""
            it.title = response.body.string()
        }

        override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/chapters${manga.url}")

        override fun chapterListParse(response: Response): List<SChapter> = listOf(
            SChapter.create().also {
                it.url = ""
                it.name = response.body.string()
            },
        )

        override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/pages${chapter.url}")

        override fun pageListParse(response: Response): List<Page> = listOf(Page(0, imageUrl = response.body.string()))

        override fun imageUrlParse(response: Response): String = response.body.string()

        private fun singleManga(response: Response) = MangasPage(
            listOf(SManga.create().also { it.url = response.body.string(); it.title = "" }),
            hasNextPage = false,
        )
    }
}
