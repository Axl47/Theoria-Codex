// Derived from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video

/**
 * One anime catalogue an extension provides. Serves extension interfaces 14 and 16 with the suspend API only: nearly
 * every extension in circulation, whichever version it declares, is built against the server-list ("hoster") library,
 * and none references RxJava, so the deprecated `fetch*` methods returning `rx.Observable` are absent.
 *
 * An episode's streams come in two steps: [getHosterList] names its servers, then [getVideoList] resolves one server
 * to videos. A server that arrives with [Hoster.videoList] already filled needs no second step; most interface-14
 * extensions answer that way, with one server holding every video (`toHosterList`). A source without the server list
 * answers the older single call, `getVideoList(episode)`.
 */
interface AnimeSource {
    val id: Long
    val name: String
    val lang: String get() = ""
    val supportsLatest: Boolean

    fun getFilterList(): AnimeFilterList = AnimeFilterList()

    suspend fun getPopularAnime(page: Int): AnimesPage

    suspend fun getLatestUpdates(page: Int): AnimesPage

    suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage

    suspend fun getAnimeDetails(anime: SAnime): SAnime

    suspend fun getEpisodeList(anime: SAnime): List<SEpisode>

    /** Only for an anime whose [SAnime.fetch_type] is `Seasons`: its seasons, each an anime of its own. */
    suspend fun getSeasonList(anime: SAnime): List<SAnime>

    suspend fun getHosterList(episode: SEpisode): List<Hoster>

    suspend fun getVideoList(hoster: Hoster): List<Video>

    @Deprecated("Use the hoster version instead")
    suspend fun getVideoList(episode: SEpisode): List<Video> = throw UnsupportedOperationException()
}

interface AnimeCatalogueSource : AnimeSource {
    override val lang: String
}

/** Creates several sources from one extension, usually one per language. */
interface AnimeSourceFactory {
    fun createSources(): List<AnimeSource>
}

/** Marker: the source has no rate limit to respect. */
interface UnmeteredSource
