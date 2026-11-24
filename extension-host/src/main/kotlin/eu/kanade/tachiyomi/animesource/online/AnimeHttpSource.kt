// Derived from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.online.sourceId
import eu.kanade.tachiyomi.source.online.withoutDomain
import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt

@Suppress("TooManyFunctions")
abstract class AnimeHttpSource : AnimeCatalogueSource {
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

    override suspend fun getPopularAnime(page: Int): AnimesPage = fetch(popularAnimeRequest(page)).use { popularAnimeParse(it) }

    protected open fun popularAnimeRequest(page: Int): Request = unsupported()

    protected open fun popularAnimeParse(response: Response): AnimesPage = unsupported()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
        fetch(searchAnimeRequest(page, query, filters)).use { searchAnimeParse(it) }

    protected open fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = unsupported()

    protected open fun searchAnimeParse(response: Response): AnimesPage = unsupported()

    override suspend fun getLatestUpdates(page: Int): AnimesPage = fetch(latestUpdatesRequest(page)).use { latestUpdatesParse(it) }

    protected open fun latestUpdatesRequest(page: Int): Request = unsupported()

    protected open fun latestUpdatesParse(response: Response): AnimesPage = unsupported()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = fetch(animeDetailsRequest(anime)).use { animeDetailsParse(it) }.apply { initialized = true }

    open fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    protected open fun animeDetailsParse(response: Response): SAnime = unsupported()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = fetch(episodeListRequest(anime)).use { episodeListParse(it) }

    protected open fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    protected open fun episodeListParse(response: Response): List<SEpisode> = unsupported()

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = fetch(seasonListRequest(anime)).use { seasonListParse(it) }

    protected open fun seasonListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    protected open fun seasonListParse(response: Response): List<SAnime> = unsupported()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = fetch(hosterListRequest(episode)).use { hosterListParse(it) }

    protected open fun hosterListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    protected open fun hosterListParse(response: Response): List<Hoster> = unsupported()

    override suspend fun getVideoList(hoster: Hoster): List<Video> = fetch(videoListRequest(hoster)).use { videoListParse(it, hoster) }

    protected open fun videoListRequest(hoster: Hoster): Request = GET(hoster.hosterUrl, headers)

    protected open fun videoListParse(response: Response, hoster: Hoster): List<Video> = unsupported()

    @Deprecated("Use the hoster version instead")
    override suspend fun getVideoList(episode: SEpisode): List<Video> = fetch(videoListRequest(episode)).use { videoListParse(it) }

    protected open fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    protected open fun videoListParse(response: Response): List<Video> = unsupported()

    open suspend fun resolveVideo(video: Video): Video? = video

    open fun List<Hoster>.sortHosters(): List<Hoster> = this

    open fun List<Video>.sortVideos(): List<Video> = this

    open suspend fun getVideoUrl(video: Video): String = fetch(videoUrlRequest(video)).use { videoUrlParse(it) }

    protected open fun videoUrlRequest(video: Video): Request = GET(video.url, headers)

    protected open fun videoUrlParse(response: Response): String = unsupported()

    fun SEpisode.setUrlWithoutDomain(url: String) {
        this.url = withoutDomain(url)
    }

    fun SAnime.setUrlWithoutDomain(url: String) {
        this.url = withoutDomain(url)
    }

    open fun getAnimeUrl(anime: SAnime): String = animeDetailsRequest(anime).url.toString()

    open fun getEpisodeUrl(episode: SEpisode): String = episode.url

    @Deprecated("All modifications should be done when constructing the episode")
    open fun prepareNewEpisode(episode: SEpisode, anime: SAnime) = Unit

    internal fun imageCall(url: String): Call = client.newCall(GET(url, headers))

    private suspend fun fetch(request: Request): Response = client.newCall(request).awaitSuccess()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("The source implements neither this step nor the call that uses it")
}
