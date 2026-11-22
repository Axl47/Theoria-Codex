package com.theoriacodex.mihonhost

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EXTENSION_SOURCE_ID_PREFIX
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.SeriesStatus
import com.theoriacodex.domain.series.withChoices
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One Mihon extension source as the app's [SeriesSource]: a Mihon manga is a series, a chapter an entry, and a
 * chapter's pages are image addresses [images] can fetch through the source's own client.
 */
class MihonSeriesSource(
    private val source: Source,
    private val images: ExtensionImages,
    private val log: (String) -> Unit = {},
) : SeriesSource {
    override val key: SourceKey = sourceKey(source)
    override val name: String get() = source.name
    override val language: String get() = source.lang

    /**
     * Series and chapters as the source listed them. Sources may keep data on them between calls, so a later call
     * about one hands the source its own object back; one from an earlier run is rebuilt from what the app stored.
     */
    private val listedSeries = recent<SManga>()
    private val listedChapters = recent<SChapter>()

    /** The last search asked for and the list its pages were given; another search replaces it. */
    private class PagedSearch(val text: String, val filters: List<SeriesFilter>, val options: FilterList)

    @Volatile private var pagedSearch: PagedSearch? = null

    override suspend fun popular(page: Int): SeriesPage = calling { source.getPopularManga(page).listed() }

    override val supportsLatest: Boolean get() = source.supportsLatest

    override suspend fun latest(page: Int): SeriesPage = calling { source.getLatestUpdates(page).listed() }

    override suspend fun filters(): List<SeriesFilter> = calling { source.getFilterList().toSeriesFilters() }

    /**
     * The source reads a search's options from a list it makes, so a first page sets [filters] onto a new one. Later
     * pages of the same search hand the source that list again, as Mihon does: a source that fills its filters in
     * after its first request would otherwise page with other criteria.
     */
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage = calling {
        val options = pagedSearch?.takeIf { page > 1 && it.text == text && it.filters == filters }?.options
            ?: source.getFilterList().also { options -> if (filters.isNotEmpty()) options.setFrom(options.toSeriesFilters().withChoices(filters)) }
        pagedSearch = PagedSearch(text, filters, options)
        source.getSearchManga(page, text, options).listed()
    }

    override suspend fun details(series: Series): Series = calling {
        val manga = manga(series)
        val details = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
        manga.withDetails(details).also { listedSeries[it.url] = it }.toSeries()
    }

    override suspend fun entries(series: Series): List<SeriesEntry> = calling {
        source.getMangaUpdate(manga(series), emptyList(), fetchDetails = false, fetchChapters = true).chapters.map { chapter ->
            listedChapters[chapter.url] = chapter
            SeriesEntry(
                address = chapter.url,
                name = chapter.name,
                number = chapter.chapter_number.takeIf { it >= 0f },
                translatorGroup = chapter.scanlator?.takeIf(String::isNotBlank),
                uploadedAtEpochMs = chapter.date_upload.takeIf { it > 0L },
            )
        }
    }

    override suspend fun pages(entry: SeriesEntry): List<ImageRef> = calling {
        val chapter = listedChapters[entry.address] ?: SChapter.create().apply {
            url = entry.address
            name = entry.name
            entry.number?.let { chapter_number = it }
            scanlator = entry.translatorGroup
            entry.uploadedAtEpochMs?.let { date_upload = it }
        }
        source.getPageList(chapter).mapNotNull { page -> pageImage(chapter, page) }
    }

    /** Covers stored by earlier builds carry a tag of their own, which is replaced. */
    override fun coverAddress(series: Series): String? = series.coverUrl?.substringBefore(ExtensionImages.FRAGMENT)?.let { address ->
        if (source is HttpSource) images.coverAddress(source, address) else address
    }

    private fun MangasPage.listed() = SeriesPage(
        series = mangas.map { manga ->
            listedSeries[manga.url] = manga
            manga.toSeries()
        },
        hasNextPage = hasNextPage,
    )

    private fun manga(series: Series): SManga = listedSeries[series.id.address] ?: SManga.create().apply {
        url = series.id.address
        title = series.title
        thumbnail_url = series.coverUrl?.substringBefore(ExtensionImages.FRAGMENT)
        author = series.author
        genre = series.genres.joinToString().takeIf(String::isNotBlank)
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
    }

    private fun SManga.toSeries() = Series(
        id = SeriesId(key, url),
        title = title,
        coverUrl = thumbnail_url?.takeIf(String::isNotBlank),
        author = author?.takeIf(String::isNotBlank),
        genres = genre.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
        language = source.lang,
        description = description?.trim()?.takeIf(String::isNotEmpty),
        status = seriesStatus(status),
    )

    /**
     * Nothing is requested here. Some sources name each image only on a page of its own; [images] asks for that when
     * the image itself is requested, so a chapter shows at once and a page that fails is the only one that does.
     */
    private fun pageImage(chapter: SChapter, page: Page): ImageRef? {
        val address = if (source is HttpSource) images.pageAddress(source, chapter, page) else page.imageUrl
        return address?.let { ImageRef(url = it, localPath = null, mime = null) }
    }

    /**
     * Runs an extension call off the caller's thread, which is often the main one: extensions parse there and some
     * block on the network. Its failures are reported the way built-in sources report theirs, with the failure's own
     * message and never its class name, which screens would show.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> calling(block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (error: CancellationException) {
        throw error
    } catch (error: HttpException) {
        // A refusal (401, 403) is not reported as a missing key: the app holds no key for an extension source, and
        // would point the user at a Settings field that does not exist. Most are a browser challenge.
        val reason = if (error.code == HTTP_TOO_MANY_REQUESTS) SourceFailureReason.RATE_LIMITED else SourceFailureReason.NETWORK
        throw SourceAdapterException(reason, error.message, error)
    } catch (error: IOException) {
        throw SourceAdapterException(SourceFailureReason.NETWORK, error.message, error)
    } catch (error: LinkageError) {
        // The extension called a part of Mihon's host API this module does not provide.
        log("${source.name} needs host API this module lacks: $error")
        throw SourceAdapterException(SourceFailureReason.UNKNOWN, error.message, error)
    } catch (error: Exception) {
        throw SourceAdapterException(SourceFailureReason.PARSE, error.message, error)
    }

    companion object {
        /** The app-wide identity of an extension source; the `mihon` label can never be a real site's host. */
        fun sourceKey(source: Source): SourceKey = SourceKey.parseOrThrow("$EXTENSION_SOURCE_ID_PREFIX${source.id}")

        private const val MAX_LISTED = 2_048
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** The most recent [MAX_LISTED] values by address. */
        private fun <T> recent(): MutableMap<String, T> = Collections.synchronizedMap(
            object : LinkedHashMap<String, T>() {
                override fun removeEldestEntry(eldest: Map.Entry<String, T>): Boolean = size > MAX_LISTED
            },
        )
    }
}

/** Mihon's `SManga.status` constant as the app's status; a number Mihon does not define is unknown. */
internal fun seriesStatus(status: Int): SeriesStatus = when (status) {
    SManga.ONGOING -> SeriesStatus.ONGOING
    SManga.COMPLETED -> SeriesStatus.COMPLETED
    SManga.LICENSED -> SeriesStatus.LICENSED
    SManga.PUBLISHING_FINISHED -> SeriesStatus.PUBLISHING_FINISHED
    SManga.CANCELLED -> SeriesStatus.CANCELLED
    SManga.ON_HIATUS -> SeriesStatus.ON_HIATUS
    else -> SeriesStatus.UNKNOWN
}
