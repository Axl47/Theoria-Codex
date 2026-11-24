package com.theoriacodex.extensionhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.quickjs.QuickJs
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimeHarnessTest {
    private val harness = Harness(TAG)

    @Test
    fun animeExtensionStreamsEndToEnd() = runBlocking {
        val apkName = harness.apkNamed("animeApk")
        assumeNotNull(apkName)
        val (_, sources) = harness.load(apkName!!, HostServices(ExtensionImages()) { _, host -> report("source", "challenged at $host") })
        val source = sources.firstOrNull { it.language == "en" } ?: sources.first()
        val series = source.series
        assertTrue("${source.name} is not served by the anime adapter", series is AniyomiSeriesSource)
        report("source", "${source.name} lang=${source.language} kind=${series.kind} supportsLatest=${series.supportsLatest}")

        val listing = harness.timed("popular") { series.popular(1) }
        report("popular", "${listing.series.size} series, covers=${listing.series.count { it.coverUrl != null }}, more=${listing.hasNextPage}")
        assertTrue("The popular listing is empty", listing.series.isNotEmpty())

        val episode = firstEpisode(series, listing.series)
        val streams = harness.timed("streams") { (series.media(episode) as EntryMedia.Streams).streams }
        report("streams", "${streams.size} streams, types=${streams.groupingBy { it.mime ?: "unstated" }.eachCount()}")
        report("streams", "with headers=${streams.count { it.headers.isNotEmpty() }}, subtitles=${streams.sumOf { it.subtitles.size }}, audio=${streams.sumOf { it.audioTracks.size }}")
        assertTrue("The episode has no streams", streams.isNotEmpty())
        report("first stream", runCatching { withContext(Dispatchers.IO) { opening(streams.first()) } }.getOrElse { "failed: $it" })
        report("quickjs", "evaluated a script=${quickJsLoaded()}")
    }

    @Test
    fun quickJsEvaluates() {
        val answer = QuickJs.create().use { it.evaluate("[6, 7].reduce((a, b) => a * b)") }

        assertEquals(42, (answer as Number).toInt())
        assertTrue("QuickJS ran without its native library mapped", quickJsLoaded())
    }

    private fun quickJsLoaded(): Boolean = File("/proc/self/maps").readText().contains("libquickjs")

    private suspend fun firstEpisode(source: SeriesSource, listed: List<Series>): SeriesEntry =
        listed.take(MAX_SERIES_TRIED).firstNotNullOfOrNull { series ->
            val details = harness.timed("details") { source.details(series) }
            val entries = harness.timed("episodes") { source.entries(details, stored = emptyList()).entries }
            report("episodes", "${entries.size} episodes")
            entries.lastOrNull()
        } ?: error("None of the first $MAX_SERIES_TRIED series had an episode")

    private fun opening(stream: MediaStream): String {
        val request = Request.Builder().url(stream.url).header("Range", "bytes=0-2047").apply { stream.headers.forEach { (name, value) -> header(name, value) } }.build()
        return OkHttpClient().newCall(request).execute().use { response ->
            val head = response.body.byteStream().readNBytes(PROBE_BYTES)
            val text = String(head, Charsets.ISO_8859_1)
            val kind = when {
                text.startsWith("#EXTM3U") -> "HLS playlist"
                text.contains("<MPD") -> "DASH manifest"
                text.regionMatches(4, "ftyp", 0, 4) -> "MP4"
                head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() -> "WebM"
                else -> "not media"
            }
            "$kind, HTTP ${response.code}"
        }
    }

    private fun report(key: String, message: String) = harness.report(key, message)

    private companion object {
        const val TAG = "AniyomiHarness"
        const val MAX_SERIES_TRIED = 5
        const val PROBE_BYTES = 2048
    }
}
