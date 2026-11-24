package com.theoriacodex.extensionhost

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntries
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

class MihonSeriesSource(private val source: Source, host: HostServices) : SeriesSource {
    override val key: SourceKey = MihonKind.sourceKey(source.id)
    private val calls = ExtensionCalls(source.name) { challenged -> host.onChallenged(key, challenged) }

    private val listedSeries = recentlyListed<SManga>()
    private val listedChapters = recentlyListed<SChapter>()

    internal val sourceImages: MihonImages? = (source as? HttpSource)?.let { MihonImages(it, key, host.images) { url -> listedChapters[url] } }
    override val name: String get() = source.name
    override val language: String get() = source.lang
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
        val memo = manga.memo
        val details = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
        manga.withDetails(details).also { if (it.memo.isEmpty()) it.memo = memo }.also { listedSeries[it.url] = it }.toSeries()
    }

    override suspend fun entries(series: Series, stored: List<SeriesEntry>): SeriesEntries = calls.calling {
        val manga = manga(series)
        val before = manga.memo
        val update = source.getMangaUpdate(manga, stored.map(::chapter), fetchDetails = false, fetchChapters = true)
        manga.memo = update.manga.memo
        val entries = update.chapters.distinctBy { it.url }.map { chapter ->
            listedChapters[chapter.url] = chapter
            extensionEntry(series.title, chapter.url, chapter.name, chapter.chapter_number, chapter.scanlator, chapter.date_upload, chapter.memo)
        }
        SeriesEntries(entries, manga.memo.storedMemo() ?: EMPTY_MEMO.takeIf { before.isNotEmpty() })
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
        }.also { listedSeries[it.url] = it }
    }

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

    private fun SManga.withDetails(details: SManga): SManga = copy().also { series ->
        runCatching { details.title }.getOrNull()?.takeIf(String::isNotBlank)?.let { series.title = it }
        details.thumbnail_url?.takeIf(String::isNotBlank)?.let { series.thumbnail_url = it }
        details.author?.takeIf(String::isNotBlank)?.let { series.author = it }
        details.genre?.takeIf(String::isNotBlank)?.let { series.genre = it }
        details.description?.takeIf(String::isNotBlank)?.let { series.description = it }
        if (details.status != SManga.UNKNOWN) series.status = details.status
        details.memo.takeIf { it.isNotEmpty() }?.let { series.memo = it }
    }

    private fun SManga.toSeries() = extensionSeries(SeriesId(key, url), title, thumbnail_url, author, genre, source.lang, description, status, memo)

    private fun pageImage(chapter: SChapter, page: Page): ImageRef? {
        val address = if (sourceImages != null) sourceImages.pageAddress(chapter, page) else page.imageUrl
        return address?.let { ImageRef(url = it, localPath = null, mime = null) }
    }
}
