// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate

/** One catalogue an extension provides (extension-lib 1.6, suspend API only; the deprecated RxJava methods are absent). */
interface Source {
    val id: Long
    val name: String
    val lang: String get() = ""
    val supportsLatest: Boolean

    fun getFilterList(): FilterList = FilterList()

    suspend fun getPopularManga(page: Int): MangasPage

    suspend fun getLatestUpdates(page: Int): MangasPage

    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage

    /** Details and chapter list in one call; each is fetched only when its flag is set. */
    suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate

    suspend fun getPageList(chapter: SChapter): List<Page>
}

interface CatalogueSource : Source {
    override val lang: String
}

/** Creates several sources from one extension, usually one per language. */
interface SourceFactory {
    fun createSources(): List<Source>
}

/** Marker: the source has no rate limit to respect. */
interface UnmeteredSource
