package com.theoriacodex.mihonhost

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EXTENSION_SOURCE_ID_PREFIX
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException

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

    init {
        if (source is HttpSource) images.attach(source)
    }

    override suspend fun popular(page: Int): SeriesPage = calling { source.getPopularManga(page).listed() }


    override suspend fun search(text: String, page: Int): SeriesPage =
        calling { source.getSearchManga(page, text, source.getFilterList()).listed() }

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
        source.getPageList(chapter).mapNotNull { page -> pageImage(page) }
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
    }

    private fun SManga.toSeries() = Series(
        id = SeriesId(key, url),
        title = title,
        coverUrl = thumbnail_url?.takeIf(String::isNotBlank)?.let { address ->
            if (source is HttpSource) images.coverAddress(source, address) else address
        },
        author = author?.takeIf(String::isNotBlank),
        genres = genre.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
        language = source.lang,
    )

    /** Some sources name each image only on a page of its own; those cost one more request per page here. */
    private suspend fun pageImage(page: Page): ImageRef? {
        if (source !is HttpSource) return page.imageUrl?.let { ImageRef(url = it, localPath = null, mime = null) }
        if (page.imageUrl == null) page.imageUrl = source.getImageUrl(page)
        return ImageRef(url = images.pageAddress(source, page), localPath = null, mime = null)
    }

    /** Reports an extension's failures the way built-in sources report theirs. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> calling(block: suspend () -> T): T = try {
        block()
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
        throw SourceAdapterException(SourceFailureReason.UNKNOWN, error.toString(), error)
    } catch (error: Exception) {
        throw SourceAdapterException(SourceFailureReason.PARSE, error.toString(), error)
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
