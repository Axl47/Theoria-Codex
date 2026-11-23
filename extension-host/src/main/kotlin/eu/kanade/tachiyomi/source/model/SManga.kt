// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
@file:Suppress("PropertyName", "VariableNaming")

package eu.kanade.tachiyomi.source.model

import java.io.Serializable
import kotlinx.serialization.json.JsonObject

/** A series as a source describes it. [url] identifies it to that source. */
interface SManga : Serializable {
    var url: String
    var title: String
    var thumbnail_url: String?
    var artist: String?
    var author: String?
    var status: Int
    var description: String?
    var genre: String?
    var update_strategy: UpdateStrategy
    var initialized: Boolean

    /** Free-form data the source keeps with the series between calls. */
    var memo: JsonObject

    fun getGenres(): List<String>? =
        genre?.takeIf(String::isNotBlank)?.split(", ")?.map(String::trim)?.filter(String::isNotBlank)?.distinct()

    fun copy(): SManga = create().also {
        it.url = url
        it.title = title
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre
        it.status = status
        it.thumbnail_url = thumbnail_url
        it.update_strategy = update_strategy
        it.initialized = initialized
        it.memo = memo
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SManga = SMangaImpl()
    }
}

class SMangaImpl : SManga {
    override lateinit var url: String
    override lateinit var title: String
    override var thumbnail_url: String? = null
    override var artist: String? = null
    override var author: String? = null
    override var status: Int = 0
    override var description: String? = null
    override var genre: String? = null
    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
    override var initialized: Boolean = false
    override var memo: JsonObject = JsonObject(emptyMap())
}

enum class UpdateStrategy {
    ALWAYS_UPDATE,
    ONLY_FETCH_ONCE,
}

class SMangaUpdate(val manga: SManga, val chapters: List<SChapter>)

class MangasPage(val mangas: List<SManga>, val hasNextPage: Boolean) {
    fun copy(mangas: List<SManga> = this.mangas, hasNextPage: Boolean = this.hasNextPage): MangasPage = MangasPage(mangas, hasNextPage)
}
