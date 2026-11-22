// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
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
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt

/**
 * Base class of nearly every extension source. A source either overrides the suspend functions, or only the
 * request/parse pairs below, which the suspend functions here then drive. Mihon drives those pairs through RxJava;
 * this host calls them directly, so RxJava is not needed.
 */
@Suppress("TooManyFunctions")
abstract class HttpSource : CatalogueSource {
    protected val network: NetworkHelper by lazy { Injekt.getInstance(NetworkHelper::class.java) }

    abstract val baseUrl: String

    open fun getHomeUrl(): String = baseUrl

    open val versionId: Int = 1

    /** Stable across installs: the first eight bytes of MD5("name/lang/versionId"), as Mihon derives it. */
    override val id: Long by lazy { generateId(name, lang, versionId) }

    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient get() = network.client

    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val bytes = MessageDigest.getInstance("MD5").digest("${name.lowercase()}/$lang/$versionId".toByteArray())
        return (0 until Long.SIZE_BYTES).map { bytes[it].toLong() and BYTE_MASK shl Byte.SIZE_BITS * (Long.SIZE_BYTES - 1 - it) }
            .reduce(Long::or) and Long.MAX_VALUE
    }

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().add("User-Agent", network.defaultUserAgentProvider())

    override fun toString(): String = "$name (${lang.uppercase()})"

    override suspend fun getPopularManga(page: Int): MangasPage = popularMangaParse(fetch(popularMangaRequest(page)))

    protected open fun popularMangaRequest(page: Int): Request = unsupported()

    protected open fun popularMangaParse(response: Response): MangasPage = unsupported()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        searchMangaParse(fetch(searchMangaRequest(page, query, filters)))

    protected open fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unsupported()

    protected open fun searchMangaParse(response: Response): MangasPage = unsupported()

    override suspend fun getLatestUpdates(page: Int): MangasPage = latestUpdatesParse(fetch(latestUpdatesRequest(page)))

    protected open fun latestUpdatesRequest(page: Int): Request = unsupported()

    protected open fun latestUpdatesParse(response: Response): MangasPage = unsupported()

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val details = if (fetchDetails) async { mangaDetailsParse(fetch(mangaDetailsRequest(manga))).apply { initialized = true } } else null
        val listed = if (fetchChapters) async { chapterListParse(fetch(chapterListRequest(manga))) } else null
        SMangaUpdate(details?.await() ?: manga, listed?.await() ?: chapters)
    }

    open fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    protected open fun mangaDetailsParse(response: Response): SManga = unsupported()

    protected open fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    protected open fun chapterListParse(response: Response): List<SChapter> = unsupported()

    override suspend fun getPageList(chapter: SChapter): List<Page> = pageListParse(fetch(pageListRequest(chapter)))

    protected open fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    protected open fun pageListParse(response: Response): List<Page> = unsupported()

    /** The image address of a page whose [Page.imageUrl] the page list left empty. */
    open suspend fun getImageUrl(page: Page): String = imageUrlParse(fetch(imageUrlRequest(page)))

    protected open fun imageUrlRequest(page: Page): Request = GET(page.url, headers)

    protected open fun imageUrlParse(response: Response): String = unsupported()

    suspend fun getImage(page: Page): Response = imageCall(page).awaitSuccess()

    protected open fun imageRequest(page: Page): Request = GET(checkNotNull(page.imageUrl) { "Page ${page.index} has no image URL" }, headers)

    /** The request for a page's image on the source's own client, which carries its headers, tokens and interceptors. */
    internal fun imageCall(page: Page): Call = client.newCall(imageRequest(page))

    /** A plain request for an image on the source's own client with its headers, as Mihon fetches covers. */
    internal fun imageCall(url: String): Call = client.newCall(GET(url, headers))

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = withoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = withoutDomain(url)
    }

    private fun withoutDomain(original: String): String = try {
        val uri = URI(original.replace(" ", "%20"))
        buildString {
            append(uri.path)
            uri.query?.let { append('?').append(it) }
            uri.fragment?.let { append('#').append(it) }
        }
    } catch (_: URISyntaxException) {
        original
    }

    open fun getMangaUrl(manga: SManga): String = mangaDetailsRequest(manga).url.toString()

    open fun getChapterUrl(chapter: SChapter): String = pageListRequest(chapter).url.toString()

    @Deprecated("All modifications should be done when constructing the chapter")
    open fun prepareNewChapter(chapter: SChapter, manga: SManga) = Unit

    private suspend fun fetch(request: Request): Response = client.newCall(request).awaitSuccess()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("The source implements neither this step nor the call that uses it")

    private companion object {
        const val BYTE_MASK = 0xffL
    }
}
