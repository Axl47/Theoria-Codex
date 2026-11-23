package com.theoriacodex.extensionhost

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.MediaTrack
import com.theoriacodex.domain.series.EntryMedia
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import java.util.IdentityHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * How [AniyomiSeriesSource] resolves an episode into the streams its servers offer, as [MihonImages] fetches a manga
 * source's images. Streams are asked for from every server at once. A server gets [Limits.serverMs] and listing the
 * servers [Limits.listMs], since some sources resolve every server inside that one call. Extension code can ignore
 * cancellation, so work past its limit, or whose caller stopped waiting, is abandoned in [HostServices.detached] and
 * never joined. Failures go through [calls], which reports each browser check that stopped a server.
 */
internal class AniyomiStreams(
    private val source: AnimeSource,
    private val host: HostServices,
    private val calls: ExtensionCalls,
    private val limits: Limits = Limits(),
) {
    private val http = source as? AnimeHttpSource
    private val hasServerList = declaresServerList(source.javaClass)

    /**
     * How long a server and the server list each get; tests shorten them. A server's limit also bounds a hidden-browser
     * pass one of its requests starts (which may take 10 s), and that is accepted: when the limit cuts a server off, its
     * work is cancelled, a request it was waiting on is cancelled with it, and the pass ends there without being
     * recorded as a failure, so the next try starts it again. A request the extension made outside any coroutine is not
     * cancelled: its pass runs on, and a clearance it earns serves the next try.
     */
    data class Limits(val serverMs: Long = SERVER_LIMIT_MS, val listMs: Long = LIST_LIMIT_MS)

    /**
     * Every server's streams in one list, in the order the source's settings prefer. Some servers answering is
     * enough. When none has a video, the most useful failure is thrown, else running out of time is; servers that
     * answered with no videos give an empty list.
     */
    suspend fun of(episode: SEpisode): EntryMedia.Streams {
        val servers = abandoning("the server list", limits.listMs) { servers(episode) } ?: throw noServerAnswered()
        // Null for a server that ran out of time.
        val answers = coroutineScope {
            servers.map { server -> async { attempt { abandoning(server.hosterName, limits.serverMs) { videos(server) } } } }.awaitAll()
        }
        // Each is made the app's failure even when another server answers, so a browser check that stopped one is reported.
        val failures = answers.mapNotNull { it.exceptionOrNull() }.map(calls::failure)
        val found = answers.zip(servers).flatMap { (answer, server) -> answer.getOrNull().orEmpty().map { it to server } }
        if (found.isEmpty()) {
            failures.mostUseful()?.let { throw it }
            if (answers.any { it.isSuccess && it.getOrNull() == null }) throw noServerAnswered()
            return EntryMedia.Streams(emptyList())
        }
        // By identity: two servers can offer equal videos, and each keeps its own server's name.
        val serverOf = found.toMap(IdentityHashMap())
        val videos = found.map { it.first }.let { all -> http?.run { all.sortVideos() } ?: all }
        // Sorting is stable, so a video the source marks as preferred comes first and the source's order holds otherwise.
        return EntryMedia.Streams(videos.sortedByDescending { it.preferred }.map { it.toStream(serverOf[it]) })
    }

    /**
     * The episode's servers in the order the source prefers. A source without the server list answers the older single
     * call, as one server that holds every video. A plain source that declares the server list in no class of its own
     * can still lack it, which only calling it shows.
     */
    @Suppress("DEPRECATION")
    private suspend fun servers(episode: SEpisode): List<Hoster> {
        val servers = if (hasServerList) {
            try {
                source.getHosterList(episode)
            } catch (_: AbstractMethodError) {
                null
            }
        } else {
            null
        } ?: source.getVideoList(episode).toHosterList()
        return http?.run { servers.sortHosters() } ?: servers
    }

    /**
     * [server]'s videos that can play: those it came with, else the ones the source finds for it. When none can, the
     * server answers with the most useful failure among them, if any failed.
     */
    private suspend fun videos(server: Hoster): List<Video> = coroutineScope {
        val outcomes = (server.videoList ?: source.getVideoList(server)).map { video -> async { attempt { finished(video) } } }.awaitAll()
        outcomes.mapNotNull { it.getOrNull() }.ifEmpty {
            outcomes.mapNotNull { it.exceptionOrNull() }.mostUseful()?.let { throw it }
            emptyList()
        }
    }

    /** The failure to show: a browser check first, since the user can pass it, else the first. */
    private fun List<Throwable>.mostUseful(): Throwable? = firstOrNull { it.isBrowserCheck() } ?: firstOrNull()

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

    /**
     * A manifest type the address gives away, or else the quality's name; the player works out anything else. The
     * address decides first: a server named "HLS" can also offer a plain file.
     */
    private fun Video.impliedMime(): String? {
        val path = videoUrl.toHttpUrlOrNull()?.encodedPath.orEmpty().lowercase()
        return when {
            ".m3u8" in path -> HLS_MIME
            ".mpd" in path -> DASH_MIME
            FILE_EXTENSIONS.any { path.endsWith(it) } -> null
            HLS_NAME.containsMatchIn(videoTitle) -> HLS_MIME
            else -> null
        }
    }

    /**
     * [block]'s result, or null once [limitMs] pass or the caller stops waiting. The work runs in the host's scope, so
     * either way it is asked to stop and left to finish there, never waited for.
     */
    private suspend fun <T : Any> abandoning(what: String, limitMs: Long, block: suspend () -> T): T? {
        val work = host.detached.async { block() }
        try {
            val result = withTimeoutOrNull(limitMs) { work.await() }
            if (result == null) host.log("${source.name}: $what took longer than $limitMs ms")
            return result
        } finally {
            work.cancel()
        }
    }

    /** [block]'s outcome, with a failure kept and logged; the caller's cancellation passes through (see [extensionFailure]). */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: Throwable) {
        val failure = extensionFailure(error)
        host.log("${source.name}: $failure")
        Result.failure(failure)
    }

    private fun noServerAnswered() = SourceAdapterException(SourceFailureReason.NETWORK, "No server answered in time")

    companion object {
        /** Every server that answered in a measured sample did so within 6.2 s. */
        const val SERVER_LIMIT_MS = 6_000L

        /**
         * A server list that resolves every server itself took up to 31 s on a phone-speed network and longer on an
         * emulator. With one server's limit on top, this stays inside the 60 s the Viewer waits for an episode.
         */
        const val LIST_LIMIT_MS = 50_000L
        private const val HLS_MIME = "application/x-mpegURL"
        private const val DASH_MIME = "application/dash+xml"
        private val FILE_EXTENSIONS = listOf(".mp4", ".mkv", ".webm")
        private val HLS_NAME = Regex("""\b(hls|m3u8)\b""", RegexOption.IGNORE_CASE)
    }
}

private fun Track.toMediaTrack() = MediaTrack(url, lang)

/**
 * Whether a source class has the server list, decided as Aniyomi decides it: some class of the extension's own, from
 * [type] up to the host's classes, declares one of its steps. Shared base classes in an extension put the override in
 * a class between the two. The version an extension declares does not tell (nearly every version-14 extension has it),
 * and calling the host's default to find out would fetch the episode's page, which a source that keeps an id or a full
 * address in it cannot answer. A class that cannot be inspected is taken to have it, as nearly all do.
 */
internal fun declaresServerList(type: Class<*>): Boolean = try {
    generateSequence(type) { it.superclass }
        .takeWhile { it != Any::class.java && !it.name.startsWith(HOST_ANIME_PACKAGE) }
        .any { declared -> declared.declaredMethods.any { it.name in SERVER_LIST_STEPS } }
} catch (_: LinkageError) {
    true
}

private const val HOST_ANIME_PACKAGE = "eu.kanade.tachiyomi.animesource."
private val SERVER_LIST_STEPS = setOf("getHosterList", "hosterListParse", "hosterListSelector")
