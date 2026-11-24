package com.theoriacodex.extensionhost

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource

/**
 * One Mihon extension source as the app's [SeriesSource]: a Mihon manga is a series, a chapter an entry, and a
 * chapter's pages are image addresses [HostServices.images] can fetch through the source's own client. A host whose
 * browser check stopped one of its calls, which can be an API or image host besides its site's, is reported to
 * [HostServices.onChallenged].
 */
class MihonSeriesSource(private val source: Source, host: HostServices) : SeriesSource {
    override val key: SourceKey = MihonKind.sourceKey(source.id)
    private val calls = ExtensionCalls(source.name) { challenged -> host.onChallenged(key, challenged) }

    /** Fetches its covers and pages through its own client; only an HTTP source has one. */
    internal val sourceImages: MihonImages? = (source as? HttpSource)?.let { MihonImages(it, key, host.images) { url -> listedChapters[url] } }
    override val name: String get() = source.name
    override val language: String get() = source.lang

    /**
     * Series and chapters as the source listed them. Sources may keep data on them between calls, so a later call
     * about one hands the source its own object back; one from an earlier run is rebuilt from what the app stored,
     * memo included. A held one listed without a memo takes the stored memo, as an import or another call may have
     * given one.
     */
    private val listedSeries = recentlyListed<SManga>()
    private val listedChapters = recentlyListed<SChapter>()
    private val searches = FilterSearch(source::getFilterList) { it.nodes() }

    override suspend fun popular(page: Int): SeriesPage = calls.calling { source.getPopularManga(page).listed() }

    override val supportsLatest: Boolean get() = source.supportsLatest

    override suspend fun latest(page: Int): SeriesPage = calls.calling { source.getLatestUpdates(page).listed() }

    override suspend fun filters(): List<SeriesFilter> = calls.calling { searches.defaults() }

    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage = calls.calling {
        source.getSearchManga(page, text, searches.options(text, page, filters)).listed()
    }

    override suspend fun details(series: Series): Series = calls.calling {
        val manga = manga(series)
        val details = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
        manga.withDetails(details).also { listedSeries[it.url] = it }.toSeries()
    }

    override suspend fun entries(series: Series): List<SeriesEntry> = calls.calling {
        source.getMangaUpdate(manga(series), emptyList(), fetchDetails = false, fetchChapters = true).chapters.map { chapter ->
            listedChapters[chapter.url] = chapter
            extensionEntry(series.title, chapter.url, chapter.name, chapter.chapter_number, chapter.scanlator, chapter.date_upload, chapter.memo)
        }
    }

    override suspend fun media(entry: SeriesEntry): EntryMedia = EntryMedia.Pages(pages(entry))

    private suspend fun pages(entry: SeriesEntry): List<ImageRef> = calls.calling {
        val chapter = chapter(entry)
        source.getPageList(chapter).mapNotNull { page -> pageImage(chapter, page) }
    }

    override fun webUrl(series: Series): String? {
        val http = source as? HttpSource
        return extensionWebUrl(series, super.webUrl(series), http?.baseUrl, http?.let { { it.getMangaUrl(manga(series)) } })
    }

    private fun MangasPage.listed() = SeriesPage(
        series = mangas.map { manga ->
            listedSeries[manga.url] = manga
            manga.toSeries()
        },
        hasNextPage = hasNextPage,
    )

    private fun manga(series: Series): SManga {
        listedSeries[series.id.address]?.let { listed ->
            if (listed.memo.isEmpty()) listed.memo = sourceMemo(series.memo)
            return listed
        }
        return SManga.create().apply {
            url = series.id.address
            title = series.title
            thumbnail_url = series.coverUrl?.substringBefore(ExtensionImages.FRAGMENT)
            author = series.author
            genre = series.genres.joinToString().takeIf(String::isNotBlank)
            memo = sourceMemo(series.memo)
        }
    }

    /** As [manga], for a chapter. One rebuilt is held too, so [sourceImages] can hand it back when it lists pages again. */
    private fun chapter(entry: SeriesEntry): SChapter {
        listedChapters[entry.address]?.let { listed ->
            if (listed.memo.isEmpty()) listed.memo = sourceMemo(entry.memo)
            return listed
        }
        return SChapter.create().apply {
            url = entry.address
            name = entry.name
            entry.number?.let { chapter_number = it }
            scanlator = entry.translatorGroup
            entry.uploadedAtEpochMs?.let { date_upload = it }
            memo = sourceMemo(entry.memo)
        }.also { listedChapters[it.url] = it }
    }

    /**
     * This series with what its details page added. A source's details often leave out the address, the title or the
     * cover it already listed, so, as Mihon does, they only add to the series the app asked about.
     */
    private fun SManga.withDetails(details: SManga): SManga = copy().also { series ->
        // The title has no value until the source sets one.
        runCatching { details.title }.getOrNull()?.takeIf(String::isNotBlank)?.let { series.title = it }
        details.thumbnail_url?.takeIf(String::isNotBlank)?.let { series.thumbnail_url = it }
        details.author?.takeIf(String::isNotBlank)?.let { series.author = it }
        details.genre?.takeIf(String::isNotBlank)?.let { series.genre = it }
        details.description?.takeIf(String::isNotBlank)?.let { series.description = it }
        if (details.status != SManga.UNKNOWN) series.status = details.status
        details.memo.takeIf { it.isNotEmpty() }?.let { series.memo = it }
    }

    private fun SManga.toSeries() = extensionSeries(SeriesId(key, url), title, thumbnail_url, author, genre, source.lang, description, status, memo)

    /**
     * Nothing is requested here. Some sources name each image only on a page of its own; [sourceImages] asks for that when
     * the image itself is requested, so a chapter shows at once and a page that fails is the only one that does.
     */
    private fun pageImage(chapter: SChapter, page: Page): ImageRef? {
        val address = if (sourceImages != null) sourceImages.pageAddress(chapter, page) else page.imageUrl
        return address?.let { ImageRef(url = it, localPath = null, mime = null) }
    }
}
