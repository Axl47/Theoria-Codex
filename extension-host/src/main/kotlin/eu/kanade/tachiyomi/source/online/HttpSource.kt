// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt

@Suppress("TooManyFunctions")
abstract class HttpSource : CatalogueSource {
    protected val network: NetworkHelper by lazy { Injekt.getInstance(NetworkHelper::class.java) }

    abstract val baseUrl: String

    open fun getHomeUrl(): String = baseUrl

    open val versionId: Int = 1

    override val id: Long by lazy { generateId(name, lang, versionId) }

    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient get() = network.client

    protected fun generateId(name: String, lang: String, versionId: Int): Long = sourceId(name, lang, versionId)

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().add("User-Agent", network.defaultUserAgentProvider())

    override fun toString(): String = "$name (${lang.uppercase()})"

    override suspend fun getPopularManga(page: Int): MangasPage = fetch(popularMangaRequest(page)).use { popularMangaParse(it) }

    protected open fun popularMangaRequest(page: Int): Request = unsupported()

    protected open fun popularMangaParse(response: Response): MangasPage = unsupported()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        fetch(searchMangaRequest(page, query, filters)).use { searchMangaParse(it) }

    protected open fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unsupported()

    protected open fun searchMangaParse(response: Response): MangasPage = unsupported()

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetch(latestUpdatesRequest(page)).use { latestUpdatesParse(it) }

    protected open fun latestUpdatesRequest(page: Int): Request = unsupported()

    protected open fun latestUpdatesParse(response: Response): MangasPage = unsupported()

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val details = if (fetchDetails) async { fetch(mangaDetailsRequest(manga)).use { mangaDetailsParse(it) }.apply { initialized = true } } else null
        val listed = if (fetchChapters) async { fetch(chapterListRequest(manga)).use { chapterListParse(it) } } else null
        SMangaUpdate(details?.await() ?: manga, listed?.await() ?: chapters)
    }

    open fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    protected open fun mangaDetailsParse(response: Response): SManga = unsupported()

    protected open fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    protected open fun chapterListParse(response: Response): List<SChapter> = unsupported()

    override suspend fun getPageList(chapter: SChapter): List<Page> = fetch(pageListRequest(chapter)).use { pageListParse(it) }

    protected open fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    protected open fun pageListParse(response: Response): List<Page> = unsupported()

    open suspend fun getImageUrl(page: Page): String = fetch(imageUrlRequest(page)).use { imageUrlParse(it) }

    protected open fun imageUrlRequest(page: Page): Request = GET(page.url, headers)

    protected open fun imageUrlParse(response: Response): String = unsupported()

    suspend fun getImage(page: Page, @Suppress("UNUSED_PARAMETER") existingSize: Long = 0L): Response = imageCall(page).awaitSuccess()

    protected open fun imageRequest(page: Page): Request = GET(checkNotNull(page.imageUrl) { "Page ${page.index} has no image URL" }, headers)

    internal fun imageCall(page: Page): Call = client.newCall(imageRequest(page))

    internal fun imageCall(url: String): Call = client.newCall(GET(url, headers))

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = withoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = withoutDomain(url)
    }

    open fun getMangaUrl(manga: SManga): String = mangaDetailsRequest(manga).url.toString()

    open fun getChapterUrl(chapter: SChapter): String = pageListRequest(chapter).url.toString()

    @Deprecated("All modifications should be done when constructing the chapter")
    open fun prepareNewChapter(chapter: SChapter, manga: SManga) = Unit

    private suspend fun fetch(request: Request): Response = client.newCall(request).awaitSuccess()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("The source implements neither this step nor the call that uses it")
}
