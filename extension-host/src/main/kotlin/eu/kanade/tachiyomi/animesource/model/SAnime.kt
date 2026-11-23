// Derived from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
@file:Suppress("PropertyName", "VariableNaming")

package eu.kanade.tachiyomi.animesource.model

import java.io.Serializable

/**
 * An anime as a source describes it. [url] identifies it to that source. An anime whose [fetch_type] is
 * [FetchType.Seasons] lists seasons (each an anime of its own, numbered by [season_number]) instead of episodes.
 */
interface SAnime : Serializable {
    var url: String
    var title: String
    var thumbnail_url: String?
    var background_url: String?
    var artist: String?
    var author: String?
    var status: Int
    var description: String?
    var genre: String?
    var update_strategy: AnimeUpdateStrategy
    var fetch_type: FetchType
    var season_number: Double
    var initialized: Boolean

    fun getGenres(): List<String>? =
        genre?.takeIf(String::isNotBlank)?.split(", ")?.map(String::trim)?.filter(String::isNotBlank)?.distinct()

    fun copy(): SAnime = create().also {
        it.url = url
        it.title = title
        it.thumbnail_url = thumbnail_url
        it.background_url = background_url
        it.artist = artist
        it.author = author
        it.status = status
        it.description = description
        it.genre = genre
        it.update_strategy = update_strategy
        it.fetch_type = fetch_type
        it.season_number = season_number
        it.initialized = initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6
        const val UPCOMING = 7

        fun create(): SAnime = SAnimeImpl()
    }
}

class SAnimeImpl : SAnime {
    override lateinit var url: String
    override lateinit var title: String
    override var thumbnail_url: String? = null
    override var background_url: String? = null
    override var artist: String? = null
    override var author: String? = null
    override var status: Int = 0
    override var description: String? = null
    override var genre: String? = null
    override var update_strategy: AnimeUpdateStrategy = AnimeUpdateStrategy.ALWAYS_UPDATE
    override var fetch_type: FetchType = FetchType.Episodes
    override var season_number: Double = -1.0
    override var initialized: Boolean = false
}

enum class AnimeUpdateStrategy { ALWAYS_UPDATE, ONLY_FETCH_ONCE }

enum class FetchType { Seasons, Episodes }

class AnimesPage(val animes: List<SAnime>, val hasNextPage: Boolean)
