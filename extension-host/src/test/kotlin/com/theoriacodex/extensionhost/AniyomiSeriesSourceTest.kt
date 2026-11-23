package com.theoriacodex.extensionhost

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.MediaTrack
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesStatus
import com.theoriacodex.domain.series.UnsupportedEntriesException
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostUnavailableException
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniyomiSeriesSourceTest {
    private val sourceHeaders = mapOf("User-Agent" to "FakeBrowser/1.0", "Referer" to "https://anime.example/")

    @Test
    fun `an anime source and a manga source with the same number are different sources`() {
        val anime = AniyomiSeriesSource(ServerSource(), HostServices(ExtensionImages()))

        assertEquals("custom_aniyomi_7", anime.key.id)
        assertEquals(SeriesKind.ANIME, anime.kind)
        assertNotEquals(MihonKind.sourceKey(7), anime.key)
    }

    @Test
    fun `every server's streams come in the source's preferred order, and a stream without headers gets the source's`() = runBlocking {
        val source = ServerSource(
            servers = listOf(Hoster(hosterName = "Server A"), Hoster(hosterName = "Server B", videoList = listOf(Video("https://b.example/v.mp4", "Server B 480p")))),
            videos = mapOf(
                "Server A" to {
                    listOf(
                        Video(
                            "https://a.example/hls/master.m3u8",
                            "1080p",
                            headers = headersOf("Referer", "https://a.example/"),
                            subtitleTracks = listOf(Track("https://a.example/en.vtt", "English")),
                            audioTracks = listOf(Track("https://a.example/ja.m4a", "Japanese")),
                        ),
                        Video("https://a.example/720.mp4", "720p"),
                    )
                },
            ),
        )

        val media = streamsOf(source)

        assertEquals(
            listOf(
                // The source's settings prefer 720p.
                MediaStream("https://a.example/720.mp4", "720p · Server A", headers = sourceHeaders),
                MediaStream(
                    "https://a.example/hls/master.m3u8",
                    "1080p · Server A",
                    mime = "application/x-mpegURL",
                    headers = mapOf("Referer" to "https://a.example/"),
                    subtitles = listOf(MediaTrack("https://a.example/en.vtt", "English")),
                    audioTracks = listOf(MediaTrack("https://a.example/ja.m4a", "Japanese")),
                ),
                MediaStream("https://b.example/v.mp4", "Server B 480p", headers = sourceHeaders),
            ),
            media,
        )
    }

    @Test
    fun `a source with only the older single call answers through it without a request, its addresses asked for where it left them out`() = runBlocking {
        val source = SingleCallSource {
            listOf(Video(url = "https://old.example/watch/1", quality = "360p", videoUrl = null), Video("https://old.example/480.mp4", "480p", "https://old.example/480.mp4"))
        }

        assertEquals(
            listOf(MediaStream("https://old.example/watch/1.mp4", "360p", headers = sourceHeaders), MediaStream("https://old.example/480.mp4", "480p", headers = sourceHeaders)),
            streamsOf(source),
        )
    }

    @Test
    fun `a server that ignores cancellation delays neither the answer nor a caller that stops waiting`() = runBlocking {
        val stuck: suspend () -> List<Video> = { Thread.sleep(STUCK_MS); emptyList() }
        val fastAndStuck = ServerSource(
            servers = listOf(Hoster(hosterName = "Fast"), Hoster(hosterName = "Stuck")),
            videos = mapOf("Fast" to { listOf(Video("https://fast.example/v.mp4", "720p")) }, "Stuck" to stuck),
        )
        val answered = within(2.seconds) { streamsOf(fastAndStuck, serverLimitMs = 200) }
        assertEquals(listOf("720p · Fast"), answered.map { it.label })

        val onlyStuck = ServerSource(servers = listOf(Hoster(hosterName = "Stuck")), videos = mapOf("Stuck" to stuck))
        val failure = within(2.seconds) { assertFailsWith<SourceAdapterException> { streamsOf(onlyStuck, serverLimitMs = 200) } }
        assertEquals(SourceFailureReason.NETWORK, failure.reason)

        // A caller that leaves stops waiting at once, on a server and on the single call alike.
        for (source in listOf(onlyStuck, SingleCallSource(stuck))) {
            val lookup = async(Dispatchers.Default) { streamsOf(source) }
            delay(100)
            within(1.seconds) { lookup.cancelAndJoin() }
        }
    }

    @Test
    fun `a stub the extension left unwritten and its own timeout fail only their own call`() = runBlocking {
        val source = ServerSource(
            servers = listOf(Hoster(hosterName = "Unwritten"), Hoster(hosterName = "Impatient"), Hoster(hosterName = "Fine")),
            videos = mapOf(
                "Unwritten" to { TODO() },
                "Impatient" to { withTimeout(1) { delay(1_000) }; emptyList() },
                "Fine" to { listOf(Video("https://fine.example/v.mp4", "720p")) },
            ),
        )
        assertEquals(listOf("720p · Fine"), streamsOf(source).map { it.label })

        val unwritten = object : FakeAnimeSource() {
            override suspend fun getPopularAnime(page: Int): AnimesPage = TODO()
        }
        val failure = assertFailsWith<SourceAdapterException> { AniyomiSeriesSource(unwritten, HostServices(ExtensionImages())).popular(1) }
        assertEquals(SourceFailureReason.UNKNOWN, failure.reason)
    }

    @Test
    fun `a browser check behind every video is the answer, and one that stopped a server is heard even when another answers`() = runBlocking {
        val heard = mutableListOf<String>()
        val challenged = Hoster(hosterName = "Checked", videoList = listOf(Video(url = "https://checked.example/watch/1", quality = "720p", videoUrl = null)))
        val checkedOrBroken = ServerSource(
            servers = listOf(Hoster(hosterName = "Broken"), challenged),
            videos = mapOf("Broken" to { throw IOException("Gone") }),
            addressOf = { throw HostUnavailableException("checked.example", HostOutageKind.CHALLENGE) },
        )
        val failure = assertFailsWith<SourceAdapterException> { streamsOf(checkedOrBroken, onChallenged = { heard += it }) }
        assertTrue(failure.isBrowserCheck())
        assertEquals(listOf("checked.example"), heard)

        heard.clear()
        val checkedAndFine = ServerSource(
            servers = listOf(challenged, Hoster(hosterName = "Fine", videoList = listOf(Video("https://fine.example/v.mp4", "720p")))),
            addressOf = { throw HostUnavailableException("checked.example", HostOutageKind.CHALLENGE) },
        )
        assertEquals(listOf("720p · Fine"), streamsOf(checkedAndFine, onChallenged = { heard += it }).map { it.label })
        assertEquals(listOf("checked.example"), heard)
    }

    @Test
    fun `servers that answer with no videos give no streams, which is no failure`() = runBlocking {
        assertEquals(emptyList<MediaStream>(), streamsOf(ServerSource(servers = listOf(Hoster(hosterName = "Empty")), videos = mapOf("Empty" to { emptyList() }))))
        assertEquals(emptyList<MediaStream>(), streamsOf(ServerSource()))
    }

    @Test
    fun `an anime grouped into seasons is refused in words the series page shows, unwrapped`() = runBlocking {
        val source = ServerSource(listing = listOf(anime("/a/1", "Grouped").apply { fetch_type = FetchType.Seasons }))
        val series = AniyomiSeriesSource(source, HostServices(ExtensionImages()))

        val listed = series.popular(1).series.single()
        val refusal = assertFailsWith<UnsupportedEntriesException> { series.entries(listed) }

        assertEquals("Seasons aren't supported yet", refusal.message)
    }

    @Test
    fun `an anime announced but not yet released is upcoming`() = runBlocking {
        val source = ServerSource(listing = listOf(anime("/a/2", "Soon").apply { status = SAnime.UPCOMING }))

        assertEquals(SeriesStatus.UPCOMING, AniyomiSeriesSource(source, HostServices(ExtensionImages())).popular(1).series.single().status)
    }

    private suspend fun streamsOf(source: AnimeHttpSource, serverLimitMs: Long = 60_000, onChallenged: (String) -> Unit = {}): List<MediaStream> {
        val host = HostServices(ExtensionImages(), onChallenged = { _, challenged -> onChallenged(challenged) })
        val series = AniyomiSeriesSource(source, host, AniyomiStreams.Limits(serverMs = serverLimitMs))
        return (series.media(SeriesEntry("/episode/1", "Episode 1")) as EntryMedia.Streams).streams
    }

    private suspend fun <T> within(limit: Duration, block: suspend () -> T): T {
        val started = TimeSource.Monotonic.markNow()
        return block().also { assertTrue("Took ${started.elapsedNow()}", started.elapsedNow() < limit) }
    }

    private fun anime(url: String, title: String) = SAnime.create().also {
        it.url = url
        it.title = title
    }

    /** A source with the headers a real one builds: its browser agent and its site. */
    private abstract inner class FakeAnimeSource : AnimeHttpSource() {
        override val name = "Fake"
        override val lang = "en"
        override val baseUrl = "https://anime.example"
        override val supportsLatest = false

        override fun headersBuilder() = Headers.Builder().apply { sourceHeaders.forEach { (name, value) -> add(name, value) } }
    }

    /**
     * A source built against the server list: [videos] answers each of [servers] that came without its videos, and
     * [addressOf] gives the address of a video that came without one.
     */
    private inner class ServerSource(
        private val servers: List<Hoster> = emptyList(),
        private val videos: Map<String, suspend () -> List<Video>> = emptyMap(),
        private val listing: List<SAnime> = emptyList(),
        private val addressOf: suspend (Video) -> String = { it.url },
    ) : FakeAnimeSource() {
        override val id = 7L

        override suspend fun getPopularAnime(page: Int) = AnimesPage(listing, hasNextPage = false)

        override suspend fun getHosterList(episode: SEpisode): List<Hoster> = servers

        override suspend fun getVideoList(hoster: Hoster): List<Video> = videos.getValue(hoster.hosterName)()

        override fun List<Video>.sortVideos(): List<Video> = sortedByDescending { it.videoTitle == "720p" }

        override suspend fun getVideoUrl(video: Video): String = addressOf(video)
    }

    /**
     * A source built before the server list: it answers only the single call, and names some videos by the page they
     * are on. It makes no request of its own, so its client fails every one, as a site that keeps something other
     * than a page's address in an episode would answer the host's default server list.
     */
    private inner class SingleCallSource(private val videos: suspend () -> List<Video>) : FakeAnimeSource() {
        override val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { throw IOException("The older call makes no request") }.build()

        @Deprecated("Use the hoster version instead")
        override suspend fun getVideoList(episode: SEpisode): List<Video> = videos()

        override suspend fun getVideoUrl(video: Video): String = "${video.url}.mp4"
    }

    private companion object {
        const val STUCK_MS = 5_000L
    }
}
