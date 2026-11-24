package com.theoriacodex.extensionhost

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntries
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.UnsupportedEntriesException
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource

class AniyomiSeriesSource internal constructor(
    private val source: AnimeSource,
    host: HostServices,
    limits: AniyomiStreams.Limits,
) : SeriesSource {
    constructor(source: AnimeSource, host: HostServices) : this(source, host, AniyomiStreams.Limits())

    override val key: SourceKey = AniyomiKind.sourceKey(source.id)
    private val calls = ExtensionCalls(source.name) { challenged -> host.onChallenged(key, challenged) }
    private val http = source as? AnimeHttpSource
    private val streams = AniyomiStreams(source, host, calls, limits)

    internal val sourceImages: SourceImages? = http?.let { SourceImages { _, url, _ -> it.imageCall(url.toString()).execute() } }
    override val name: String get() = source.name
    override val language: String get() = source.lang

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

    override suspend fun details(series: Series): Series = calls.calling { opened(anime(series)).toSeries() }

    override suspend fun entries(series: Series, stored: List<SeriesEntry>): SeriesEntries {
        val anime = listedAnime[series.id.address]?.takeIf { it.initialized } ?: calls.calling { opened(anime(series)) }
        if (anime.fetch_type == FetchType.Seasons) throw UnsupportedEntriesException(SEASONS_UNSUPPORTED)
        return calls.calling {
            val episodes = source.getEpisodeList(anime).map { episode ->
                listedEpisodes[episode.url] = episode
                extensionEntry(series.title, episode.url, episode.name, episode.episode_number, episode.scanlator, episode.date_upload)
            }
            SeriesEntries(episodes)
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

    override fun webUrl(series: Series): String? =
        extensionWebUrl(series, super.webUrl(series), http?.baseUrl, http?.let { { it.getAnimeUrl(anime(series)) } })

    private fun AnimesPage.listed() = SeriesPage(
        series = animes.map { anime ->
            if (listedAnime[anime.url]?.initialized != true) listedAnime[anime.url] = anime
            anime.toSeries()
        },
        hasNextPage = hasNextPage,
    )

    private suspend fun opened(anime: SAnime): SAnime = anime.withDetails(source.getAnimeDetails(anime)).also {
        it.initialized = true
        listedAnime[it.url] = it
    }

    private fun anime(series: Series): SAnime = listedAnime[series.id.address] ?: SAnime.create().apply {
        url = series.id.address
        title = series.title
        thumbnail_url = series.coverUrl?.substringBefore(ExtensionImages.FRAGMENT)
        author = series.author
        genre = series.genres.joinToString().takeIf(String::isNotBlank)
    }

    private fun SAnime.withDetails(details: SAnime): SAnime = copy().also { anime ->
        runCatching { details.title }.getOrNull()?.takeIf(String::isNotBlank)?.let { anime.title = it }
        details.thumbnail_url?.takeIf(String::isNotBlank)?.let { anime.thumbnail_url = it }
        details.author?.takeIf(String::isNotBlank)?.let { anime.author = it }
        details.genre?.takeIf(String::isNotBlank)?.let { anime.genre = it }
        details.description?.takeIf(String::isNotBlank)?.let { anime.description = it }
        if (details.status != SAnime.UNKNOWN) anime.status = details.status
        if (details.fetch_type == FetchType.Seasons) anime.fetch_type = FetchType.Seasons
    }

    private fun SAnime.toSeries() = extensionSeries(SeriesId(key, url), title, thumbnail_url, author, genre, source.lang, description, status)

    internal companion object {
        const val SEASONS_UNSUPPORTED = "Seasons aren't supported yet"
    }
}
