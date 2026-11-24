// Derived from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video

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

    suspend fun getSeasonList(anime: SAnime): List<SAnime>

    suspend fun getHosterList(episode: SEpisode): List<Hoster>

    suspend fun getVideoList(hoster: Hoster): List<Video>

    @Deprecated("Use the hoster version instead")
    suspend fun getVideoList(episode: SEpisode): List<Video> = throw UnsupportedOperationException()
}

interface AnimeCatalogueSource : AnimeSource {
    override val lang: String
}

interface AnimeSourceFactory {
    fun createSources(): List<AnimeSource>
}

interface UnmeteredSource
