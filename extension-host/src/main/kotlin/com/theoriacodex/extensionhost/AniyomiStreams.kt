package com.theoriacodex.extensionhost

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.diagnostics.Diagnostics
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

internal class AniyomiStreams(
    private val source: AnimeSource,
    private val host: HostServices,
    private val calls: ExtensionCalls,
    internal val limits: Limits = Limits(),
) {
    private val http = source as? AnimeHttpSource
    private val hasServerList = declaresServerList(source.javaClass)

    data class Limits(val serverMs: Long = SERVER_LIMIT_MS, val listMs: Long = LIST_LIMIT_MS)

    suspend fun of(episode: SEpisode): EntryMedia.Streams {
        val servers = abandoning("the server list", limits.listMs) { servers(episode) } ?: throw noServerAnswered()
        val answers = coroutineScope {
            servers.map { server -> async { attempt { abandoning(server.hosterName, limits.serverMs) { videos(server) } } } }.awaitAll()
        }
        val failures = answers.mapNotNull { it.exceptionOrNull() }.map(calls::failure)
        val found = answers.zip(servers).flatMap { (answer, server) -> answer.getOrNull().orEmpty().map { it to server } }
        if (found.isEmpty()) {
            failures.mostUseful()?.let { throw it }
            if (answers.any { it.isSuccess && it.getOrNull() == null }) throw noServerAnswered()
            return EntryMedia.Streams(emptyList())
        }
        val serverOf = found.toMap(IdentityHashMap())
        val videos = found.map { it.first }.let { all -> http?.run { all.sortVideos() } ?: all }
        return EntryMedia.Streams(videos.sortedByDescending { it.preferred }.map { it.toStream(serverOf[it]) })
    }

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

    private suspend fun videos(server: Hoster): List<Video> = coroutineScope {
        val outcomes = (server.videoList ?: source.getVideoList(server)).map { video -> async { attempt { finished(video) } } }.awaitAll()
        outcomes.mapNotNull { it.getOrNull() }.ifEmpty {
            outcomes.mapNotNull { it.exceptionOrNull() }.mostUseful()?.let { throw it }
            emptyList()
        }
    }

    private fun List<Throwable>.mostUseful(): Throwable? = firstOrNull { it.isBrowserCheck() } ?: firstOrNull()

    private suspend fun finished(video: Video): Video? {
        val http = http ?: return video.takeIf { it.hasAddress() }
        val resolved = http.resolveVideo(video) ?: return null
        if (!resolved.hasAddress()) resolved.videoUrl = http.getVideoUrl(resolved)
        return resolved.takeIf { it.hasAddress() }
    }

    private fun Video.hasAddress() = videoUrl.isNotBlank() && videoUrl != "null"

    private fun Video.toStream(server: Hoster?): MediaStream = MediaStream(
        url = videoUrl,
        label = label(server),
        mime = impliedMime(),
        headers = (headers?.takeIf { it.size > 0 } ?: http?.headers)?.toMap().orEmpty(),
        subtitles = subtitleTracks.map(Track::toMediaTrack),
        audioTracks = audioTracks.map(Track::toMediaTrack),
    )

    private fun Video.label(server: Hoster?): String {
        val serverName = server?.hosterName?.takeIf { it.isNotBlank() && it != Hoster.NO_HOSTER_LIST && !videoTitle.contains(it, ignoreCase = true) }
        return listOfNotNull(videoTitle.trim().takeIf(String::isNotEmpty), serverName).joinToString(" · ")
    }

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

    @Suppress("TooGenericExceptionCaught", "SuspendFunSwallowedCancellation")
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: Throwable) {
        val failure = extensionFailure(error)
        Diagnostics.report("extensions", "${source.name} stream lookup failed", failure)
        Result.failure(failure)
    }

    private fun noServerAnswered() = SourceAdapterException(SourceFailureReason.NETWORK, "No server answered in time")

    companion object {
        const val SERVER_LIMIT_MS = 6_000L

        const val LIST_LIMIT_MS = 50_000L
        private const val HLS_MIME = "application/x-mpegURL"
        private const val DASH_MIME = "application/dash+xml"
        private val FILE_EXTENSIONS = listOf(".mp4", ".mkv", ".webm")
        private val HLS_NAME = Regex("""\b(hls|m3u8)\b""", RegexOption.IGNORE_CASE)
    }
}

private fun Track.toMediaTrack() = MediaTrack(url, lang)

internal fun declaresServerList(type: Class<*>): Boolean = try {
    generateSequence(type) { it.superclass }
        .takeWhile { it != Any::class.java && !it.name.startsWith(HOST_ANIME_PACKAGE) }
        .any { declared -> declared.declaredMethods.any { it.name in SERVER_LIST_STEPS } }
} catch (_: LinkageError) {
    true
}

private const val HOST_ANIME_PACKAGE = "eu.kanade.tachiyomi.animesource."
private val SERVER_LIST_STEPS = setOf("getHosterList", "hosterListParse", "hosterListSelector")
