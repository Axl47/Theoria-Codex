package com.theoriacodex.extensionhost

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.SeriesStatus
import com.theoriacodex.domain.series.UnsupportedEntriesException
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource

/**
 * One Aniyomi extension source as the app's [SeriesSource]: an anime is a series, an episode an entry, and an
 * episode's media is the streams its servers offer, which [AniyomiStreams] resolves. Covers load through the source's
 * own client, tagged by [HostServices.images]; a host whose browser check stopped one of its calls is reported to
 * [HostServices.onChallenged].
 */
class AniyomiSeriesSource internal constructor(
    private val source: AnimeSource,
    private val host: HostServices,
    limits: AniyomiStreams.Limits,
) : SeriesSource {
    constructor(source: AnimeSource, host: HostServices) : this(source, host, AniyomiStreams.Limits())

    override val key: SourceKey = AnimeKind.sourceKey(source.id)
    private val calls = ExtensionCalls(source.name, host.log) { challenged -> host.onChallenged(key, challenged) }
    private val http = source as? AnimeHttpSource
    private val streams = AniyomiStreams(source, host, limits)

    /** Fetches its covers through its own client, with its headers; only an HTTP source has one. */
    internal val sourceImages: SourceImages? = http?.let { SourceImages { _, url, _ -> it.imageCall(url.toString()).execute() } }
    override val name: String get() = source.name
    override val language: String get() = source.lang

    /**
     * Anime and episodes as the source listed them. Sources may keep data on them between calls, so a later call
     * about one hands the source its own object back; one from an earlier run is rebuilt from what the app stored.
     */
    private val listedAnime = recentlyListed<SAnime>()
    private val listedEpisodes = recentlyListed<SEpisode>()
    private val searches = FilterSearch(source::getFilterList) { it.nodes() }

    override suspend fun popular(page: Int): SeriesPage = calls.calling { source.getPopularAnime(page).listed() }

    override val supportsLatest: Boolean get() = source.supportsLatest

    override suspend fun latest(page: Int): SeriesPage = calls.calling { source.getLatestUpdates(page).listed() }

    override suspend fun filters(): List<SeriesFilter> = calls.calling { searches.defaults() }

    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>): SeriesPage = calls.calling {
        source.getSearchAnime(page, text, searches.options(text, page, filters)).listed()
    }

    override suspend fun details(series: Series): Series = calls.calling {
        val anime = anime(series)
        anime.withDetails(source.getAnimeDetails(anime)).also { listedAnime[it.url] = it }.toSeries()
    }

    /** An anime whose source groups its episodes into seasons is refused, in words the series page shows as they are. */
    override suspend fun entries(series: Series): List<SeriesEntry> {
        val anime = anime(series)
        if (anime.fetch_type == FetchType.Seasons) throw UnsupportedEntriesException(SEASONS_UNSUPPORTED)
        return calls.calling {
            source.getEpisodeList(anime).map { episode ->
                listedEpisodes[episode.url] = episode
                SeriesEntry(
                    address = episode.url,
                    name = episode.name,
                    // Extensions may leave the number at -1 for the app to read from the name, as Aniyomi does.
                    number = episode.episode_number.takeIf { it >= 0f } ?: ChapterRecognition.parseChapterNumber(series.title, episode.name),
                    translatorGroup = episode.scanlator?.takeIf(String::isNotBlank),
                    uploadedAtEpochMs = episode.date_upload.takeIf { it > 0L },
                )
            }
        }
    }

    override suspend fun media(entry: SeriesEntry): EntryMedia = calls.calling {
        val episode = listedEpisodes[entry.address] ?: SEpisode.create().apply {
            url = entry.address
            name = entry.name
            entry.number?.let { episode_number = it }
            scanlator = entry.translatorGroup
            entry.uploadedAtEpochMs?.let { date_upload = it }
        }
        streams.of(episode)
    }

    override fun coverAddress(series: Series): String? = series.coverUrl?.let { address -> if (sourceImages != null) host.images.tag(key, address) else address }

    private fun AnimesPage.listed() = SeriesPage(
        series = animes.map { anime ->
            listedAnime[anime.url] = anime
            anime.toSeries()
        },
        hasNextPage = hasNextPage,
    )

    private fun anime(series: Series): SAnime = listedAnime[series.id.address] ?: SAnime.create().apply {
        url = series.id.address
        title = series.title
        thumbnail_url = series.coverUrl
        author = series.author
        genre = series.genres.joinToString().takeIf(String::isNotBlank)
    }

    /**
     * This anime with what its details page added. Details often leave out what the source already listed, so, as
     * Aniyomi does, they only add to the anime the app asked about, grouping into seasons included.
     */
    private fun SAnime.withDetails(details: SAnime): SAnime = copy().also { anime ->
        // The title has no value until the source sets one.
        runCatching { details.title }.getOrNull()?.takeIf(String::isNotBlank)?.let { anime.title = it }
        details.thumbnail_url?.takeIf(String::isNotBlank)?.let { anime.thumbnail_url = it }
        details.author?.takeIf(String::isNotBlank)?.let { anime.author = it }
        details.genre?.takeIf(String::isNotBlank)?.let { anime.genre = it }
        details.description?.takeIf(String::isNotBlank)?.let { anime.description = it }
        if (details.status != SAnime.UNKNOWN) anime.status = details.status
        if (details.fetch_type == FetchType.Seasons) anime.fetch_type = FetchType.Seasons
    }

    private fun SAnime.toSeries() = Series(
        id = SeriesId(key, url),
        title = title,
        coverUrl = thumbnail_url?.takeIf(String::isNotBlank),
        author = author?.takeIf(String::isNotBlank),
        genres = genre.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
        language = source.lang,
        description = description?.trim()?.takeIf(String::isNotEmpty),
        status = if (status == SAnime.UPCOMING) SeriesStatus.UPCOMING else seriesStatus(status),
    )

    internal companion object {
        const val SEASONS_UNSUPPORTED = "Seasons aren't supported yet"
    }
}
