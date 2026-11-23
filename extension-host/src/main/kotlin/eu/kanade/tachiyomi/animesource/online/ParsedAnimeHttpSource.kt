// Derived from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** An [AnimeHttpSource] that parses HTML pages by CSS selectors; most interface-14 extensions extend it. */
@Suppress("TooManyFunctions")
abstract class ParsedAnimeHttpSource : AnimeHttpSource() {
    override fun popularAnimeParse(response: Response): AnimesPage =
        listing(response, popularAnimeSelector(), popularAnimeNextPageSelector(), ::popularAnimeFromElement)

    protected abstract fun popularAnimeSelector(): String

    protected abstract fun popularAnimeFromElement(element: Element): SAnime

    protected abstract fun popularAnimeNextPageSelector(): String?

    override fun searchAnimeParse(response: Response): AnimesPage =
        listing(response, searchAnimeSelector(), searchAnimeNextPageSelector(), ::searchAnimeFromElement)

    protected abstract fun searchAnimeSelector(): String

    protected abstract fun searchAnimeFromElement(element: Element): SAnime

    protected abstract fun searchAnimeNextPageSelector(): String?

    override fun latestUpdatesParse(response: Response): AnimesPage =
        listing(response, latestUpdatesSelector(), latestUpdatesNextPageSelector(), ::latestUpdatesFromElement)

    protected abstract fun latestUpdatesSelector(): String

    protected abstract fun latestUpdatesFromElement(element: Element): SAnime

    protected abstract fun latestUpdatesNextPageSelector(): String?

    override fun animeDetailsParse(response: Response): SAnime = animeDetailsParse(response.asJsoup())

    protected abstract fun animeDetailsParse(document: Document): SAnime

    override fun episodeListParse(response: Response): List<SEpisode> = response.asJsoup().select(episodeListSelector()).map(::episodeFromElement)

    protected abstract fun episodeListSelector(): String

    protected abstract fun episodeFromElement(element: Element): SEpisode

    override fun seasonListParse(response: Response): List<SAnime> = response.asJsoup().select(seasonListSelector()).map(::seasonFromElement)

    protected abstract fun seasonListSelector(): String

    protected abstract fun seasonFromElement(element: Element): SAnime

    override fun hosterListParse(response: Response): List<Hoster> = response.asJsoup().select(hosterListSelector()).map(::hosterFromElement)

    protected abstract fun hosterListSelector(): String

    protected abstract fun hosterFromElement(element: Element): Hoster

    @Deprecated("Use the hoster version instead")
    override fun videoListParse(response: Response): List<Video> = response.asJsoup().select(videoListSelector()).map(::videoFromElement)

    protected abstract fun videoListSelector(): String

    protected abstract fun videoFromElement(element: Element): Video

    override fun videoUrlParse(response: Response): String = videoUrlParse(response.asJsoup())

    protected abstract fun videoUrlParse(document: Document): String

    private fun listing(response: Response, selector: String, nextPageSelector: String?, parse: (Element) -> SAnime): AnimesPage {
        val document = response.asJsoup()
        return AnimesPage(document.select(selector).map(parse), nextPageSelector?.let { document.selectFirst(it) } != null)
    }
}
