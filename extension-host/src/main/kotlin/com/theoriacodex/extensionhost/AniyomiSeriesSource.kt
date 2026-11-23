package com.theoriacodex.extensionhost

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.MediaTrack
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
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import java.util.IdentityHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * One Aniyomi extension source as the app's [SeriesSource]: an anime is a series, an episode an entry, and an
 * episode's media is the streams its servers offer. Covers load through the source's own client, tagged by
 * [HostServices.images]; a host whose browser check stopped one of its calls is reported to [HostServices.onChallenged].
 *
 * Streams are asked for from every server at once. A server gets [serverLimitMs] and listing the servers
 * [listLimitMs], since some sources resolve every server inside that one call. Extension code can ignore cancellation,
 * so work past its limit, or whose caller stopped waiting, is abandoned in [HostServices.abandoned] and never joined.
 */
class AniyomiSeriesSource(
    private val source: AnimeSource,
    private val host: HostServices,
    private val serverLimitMs: Long = SERVER_LIMIT_MS,
    private val listLimitMs: Long = LIST_LIMIT_MS,
) : SeriesSource {
    override val key: SourceKey = AnimeKind.sourceKey(source.id)
    private val calls = ExtensionCalls(source.name, host.log) { challenged -> host.onChallenged(key, challenged) }
    private val http = source as? AnimeHttpSource

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

    /**
     * Every server's streams in one list, in the order the source's settings prefer. Some servers answering is
     * enough; none answering, in time or at all, is a failure.
     */
    override suspend fun media(entry: SeriesEntry): EntryMedia = calls.calling {
        val episode = listedEpisodes[entry.address] ?: SEpisode.create().apply {
            url = entry.address
            name = entry.name
            entry.number?.let { episode_number = it }
            scanlator = entry.translatorGroup
            entry.uploadedAtEpochMs?.let { date_upload = it }
        }
        val servers = abandoning("the server list", listLimitMs) { servers(episode) } ?: throw noServerAnswered()
        val answers = coroutineScope {
            servers.map { server -> async { attempt { abandoning(server.hosterName, serverLimitMs) { videos(server) } } } }.awaitAll()
        }
        val found = answers.zip(servers).flatMap { (answer, server) -> answer.getOrNull().orEmpty().map { it to server } }
        if (found.isEmpty()) throw answers.firstNotNullOfOrNull { it.exceptionOrNull() } ?: noServerAnswered()
        // By identity: two servers can offer equal videos, and each keeps its own server's name.
        val serverOf = found.toMap(IdentityHashMap())
        val videos = found.map { it.first }.let { all -> http?.run { all.sortVideos() } ?: all }
        // Sorting is stable, so a video the source marks as preferred comes first and the source's order holds otherwise.
        EntryMedia.Streams(videos.sortedByDescending { it.preferred }.map { it.toStream(serverOf[it]) })
    }

    override fun coverAddress(series: Series): String? = series.coverUrl?.let { address -> if (sourceImages != null) host.images.tag(key, address) else address }

    /**
     * The episode's servers in the order the source prefers. A source without the server list answers the older single
     * call, as one server that holds every video; the version it declares does not tell which it has.
     */
    @Suppress("DEPRECATION")
    private suspend fun servers(episode: SEpisode): List<Hoster> {
        val servers = try {
            source.getHosterList(episode)
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: AbstractMethodError) {
            null
        } ?: source.getVideoList(episode).toHosterList()
        return http?.run { servers.sortHosters() } ?: servers
    }

    /** [server]'s videos that can play: those it came with, else the ones the source finds for it. */
    private suspend fun videos(server: Hoster): List<Video> = coroutineScope {
        (server.videoList ?: source.getVideoList(server)).map { video -> async { attempt { finished(video) }.getOrNull() } }.awaitAll().filterNotNull()
    }

    /** [video] finished by the source, with its address asked for when it came without one; null when it has none. */
    private suspend fun finished(video: Video): Video? {
        val http = http ?: return video.takeIf { it.hasAddress() }
        val resolved = http.resolveVideo(video) ?: return null
        if (!resolved.hasAddress()) resolved.videoUrl = http.getVideoUrl(resolved)
        return resolved.takeIf { it.hasAddress() }
    }

    /** Interface 14's constructor stores a missing address as the text "null". */
    private fun Video.hasAddress() = videoUrl.isNotBlank() && videoUrl != "null"

    /**
     * A stream the player can open. One without headers of its own gets the source's, its browser agent included, as
     * Aniyomi's player sends them: some video hosts refuse a request without them.
     */
    private fun Video.toStream(server: Hoster?): MediaStream = MediaStream(
        url = videoUrl,
        label = label(server),
        mime = impliedMime(),
        headers = (headers?.takeIf { it.size > 0 } ?: http?.headers)?.toMap().orEmpty(),
        subtitles = subtitleTracks.map(Track::toMediaTrack),
        audioTracks = audioTracks.map(Track::toMediaTrack),
    )

    /** The quality as the source names it, then its server's name unless the quality already says it. */
    private fun Video.label(server: Hoster?): String {
        val serverName = server?.hosterName?.takeIf { it.isNotBlank() && it != Hoster.NO_HOSTER_LIST && !videoTitle.contains(it, ignoreCase = true) }
        return listOfNotNull(videoTitle.trim().takeIf(String::isNotEmpty), serverName).joinToString(" · ")
    }

    /** A manifest type the address or the quality's name gives away; the player works out anything else. */
    private fun Video.impliedMime(): String? {
        val path = videoUrl.toHttpUrlOrNull()?.encodedPath.orEmpty().lowercase()
        return when {
            ".m3u8" in path || HLS_NAME.containsMatchIn(videoTitle) -> HLS_MIME
            ".mpd" in path -> DASH_MIME
            else -> null
        }
    }

    /**
     * [block]'s result, or null once [limitMs] pass or the caller stops waiting. The work runs in the host's scope, so
     * either way it is asked to stop and left to finish there, never waited for.
     */
    private suspend fun <T : Any> abandoning(what: String, limitMs: Long, block: suspend () -> T): T? {
        val work = host.abandoned.async { block() }
        try {
            val result = withTimeoutOrNull(limitMs) { work.await() }
            if (result == null) host.log("${source.name}: $what took longer than $limitMs ms")
            return result
        } finally {
            work.cancel()
        }
    }

    /** [block]'s outcome, with a failure kept and logged; cancellation of the caller passes through. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        host.log("${source.name}: $error")
        Result.failure(error)
    } catch (error: LinkageError) {
        host.log("${source.name}: $error")
        Result.failure(error)
    }

    private fun noServerAnswered() = SourceAdapterException(SourceFailureReason.NETWORK, "No server answered in time")

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
        /** Every server that answered in a measured sample did so within 6.2 s. */
        const val SERVER_LIMIT_MS = 6_000L

        /**
         * A server list that resolves every server itself took up to 31 s on a phone-speed network and longer on an
         * emulator. With one server's limit on top, this stays inside the 60 s the Viewer waits for an episode.
         */
        const val LIST_LIMIT_MS = 50_000L
        const val SEASONS_UNSUPPORTED = "Seasons aren't supported yet"
        private const val HLS_MIME = "application/x-mpegURL"
        private const val DASH_MIME = "application/dash+xml"
        private val HLS_NAME = Regex("""\b(hls|m3u8)\b""", RegexOption.IGNORE_CASE)
    }
}

private fun Track.toMediaTrack() = MediaTrack(url, lang)
