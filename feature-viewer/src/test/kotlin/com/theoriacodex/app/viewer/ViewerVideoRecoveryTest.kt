package com.theoriacodex.app.viewer

import androidx.media3.common.PlaybackException
import com.theoriacodex.app.viewer.ViewerRecoveryDecision.AskForFreshLinks
import com.theoriacodex.app.viewer.ViewerRecoveryDecision.Fail
import com.theoriacodex.app.viewer.ViewerRecoveryDecision.FallBack
import com.theoriacodex.app.viewer.ViewerRecoveryDecision.RetryInPlace
import com.theoriacodex.app.viewer.ViewerRecoveryEvent.NothingNew
import com.theoriacodex.app.viewer.ViewerRecoveryEvent.PlayerError
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerVideoRecoveryTest {
    private val episode = ViewerMediaKey(PostId(SourceKey.HITOMI, "episode"), 0)
    private val labels = listOf("A", "B", "C", "D", "E")
    private val badStatus = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    private val errorPage = PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
    private val decoder = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    private val unknown = PlaybackException.ERROR_CODE_UNSPECIFIED
    private val cleartext = PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED

    private data class Row(
        val name: String,
        val played: Boolean = false,
        val linksAgeMs: Long = 0,
        val pickedByHand: Boolean = false,
        val event: ViewerRecoveryEvent,
        val expected: ViewerRecoveryDecision,
    )

    @Test
    fun `a failed episode stream asks, falls back or fails by what it had done`() {
        val rows = listOf(
            Row("dead server: never played, fresh links", event = PlayerError(badStatus), expected = FallBack("B")),
            Row("dead server, any error", event = PlayerError(unknown), expected = FallBack("B")),
            Row("played, then an error status", played = true, event = PlayerError(badStatus), expected = AskForFreshLinks),
            Row("played, then an error page", played = true, event = PlayerError(errorPage), expected = AskForFreshLinks),
            Row("played, then not I/O", played = true, event = PlayerError(unknown), expected = FallBack("B")),
            Row("old links, never played", linksAgeMs = 3 * 60_000, event = PlayerError(badStatus), expected = AskForFreshLinks),
            Row("links two minutes old are not old yet", linksAgeMs = VIEWER_LINKS_OLD_AFTER_MS, event = PlayerError(badStatus), expected = FallBack("B")),
            Row("fresh links brought nothing new", played = true, event = NothingNew, expected = FallBack("B")),
            Row("decoder", event = PlayerError(decoder), expected = RetryInPlace),
            Row("picked by hand, dead server", pickedByHand = true, event = PlayerError(badStatus), expected = Fail),
            Row("picked by hand, played", pickedByHand = true, played = true, event = PlayerError(badStatus), expected = AskForFreshLinks),
            Row("picked by hand, nothing new", pickedByHand = true, event = NothingNew, expected = Fail),
            Row("plain HTTP, though it played", played = true, event = PlayerError(cleartext), expected = FallBack("B")),
            Row("plain HTTP on old links", linksAgeMs = 3 * 60_000, event = PlayerError(cleartext), expected = FallBack("B")),
            Row("plain HTTP, picked by hand", pickedByHand = true, event = PlayerError(cleartext), expected = Fail),
        )
        rows.forEach { row ->
            val recovery = recovery(ViewerStreamAttempts(), picked = labels.first().takeIf { row.pickedByHand }, linksAgeMs = row.linksAgeMs)
            if (row.played) recovery.onPlayed()
            assertEquals(row.name, row.expected, recovery.decide(row.event))
        }
    }

    @Test
    fun `a hand-picked stream that fresh links no longer offer leaves the one playing free to fall back`() {
        val recovery = recovery(ViewerStreamAttempts(), labels = listOf("B", "C"), picked = "A")
        assertEquals(FallBack("C"), recovery.decide(PlayerError(badStatus)))
    }

    @Test
    fun `links that expire again later in a long episode recover each time`() {
        val attempts = ViewerStreamAttempts()
        val first = recovery(attempts).apply { onPlayed() }
        assertEquals(AskForFreshLinks, first.decide(PlayerError(badStatus, positionMs = 600_000)))
        val recovered = recovery(attempts).apply { onPlayed(); onProgress(600_000 + VIEWER_RECOVERED_PROGRESS_MS) }
        assertEquals(AskForFreshLinks, recovered.decide(PlayerError(errorPage, positionMs = 1_200_000)))
    }

    @Test
    fun `a stream that dies at one point asks once, then falls back, then fails with the picker`() {
        val attempts = ViewerStreamAttempts()
        val labels = listOf("A", "B")
        val first = recovery(attempts, labels).apply { onPlayed() }
        assertEquals(AskForFreshLinks, first.decide(PlayerError(badStatus, positionMs = 600_000)))
        val recovered = recovery(attempts, labels).apply { onPlayed(); onProgress(598_000); onProgress(601_000) }
        assertEquals(FallBack("B"), recovered.decide(PlayerError(badStatus, positionMs = 601_000)))
        val fallback = recovery(attempts, labels, playing = "B").apply { onPlayed(); onProgress(601_000) }
        assertEquals(Fail, fallback.decide(PlayerError(badStatus, positionMs = 601_000)))
    }

    @Test
    fun `a lookup cancelled before it ended gives the ask back`() {
        val attempts = ViewerStreamAttempts()
        val first = recovery(attempts).apply { onPlayed() }
        assertEquals(AskForFreshLinks, first.decide(PlayerError(badStatus)))
        first.onAskCancelled()
        assertEquals(AskForFreshLinks, recovery(attempts).decide(PlayerError(badStatus)))
        val retried = ViewerStreamAttempts()
        recovery(retried, loadGeneration = 1).apply { assertEquals(AskForFreshLinks, opening(0)) }.onAskCancelled()
        assertEquals(AskForFreshLinks, recovery(retried, loadGeneration = 1).opening(0))
    }

    @Test
    fun `a replacing session inherits none of the last one's attempts`() {
        val attempts = ViewerStreamAttempts().forSession(ViewerSessionIdentity("first"))
        recovery(attempts).decide(PlayerError(badStatus))
        assertEquals(setOf("A"), attempts.of(ViewerMediaLoad(episode, 0)).failedStreams)
        assertEquals(setOf("A"), attempts.forSession(ViewerSessionIdentity("first")).of(ViewerMediaLoad(episode, 0)).failedStreams)
        assertEquals(emptySet<String>(), attempts.forSession(ViewerSessionIdentity("second")).of(ViewerMediaLoad(episode, 0)).failedStreams)
    }

    @Test
    fun `a load falls back at most three times, never to a stream that failed`() {
        val attempts = ViewerStreamAttempts()
        assertEquals(FallBack("B"), recovery(attempts, playing = "A").decide(PlayerError(badStatus)))
        assertEquals(FallBack("C"), recovery(attempts, playing = "B").decide(PlayerError(badStatus)))
        assertEquals(FallBack("D"), recovery(attempts, playing = "C").decide(PlayerError(badStatus)))
        assertEquals(Fail, recovery(attempts, playing = "D").decide(PlayerError(badStatus)))

        val two = ViewerStreamAttempts()
        assertEquals(FallBack("B"), recovery(two, labels = listOf("A", "B"), playing = "A").decide(PlayerError(badStatus)))
        assertEquals(Fail, recovery(two, labels = listOf("A", "B"), playing = "B").decide(PlayerError(badStatus)))

        val dropped = ViewerStreamAttempts()
        val refreshed = listOf("A", "C", "D", "E")
        assertEquals(FallBack("B"), recovery(dropped, playing = "A").decide(PlayerError(badStatus)))
        assertEquals(FallBack("C"), recovery(dropped, labels = refreshed, playing = "A").decide(PlayerError(badStatus)))
        assertEquals(FallBack("D"), recovery(dropped, labels = refreshed, playing = "C").decide(PlayerError(badStatus)))
        assertEquals(Fail, recovery(dropped, labels = refreshed, playing = "D").decide(PlayerError(badStatus)))
    }

    @Test
    fun `a decoder failure retries in place once, then the stream counts as failed`() {
        val attempts = ViewerStreamAttempts()
        val recovery = recovery(attempts)
        assertEquals(RetryInPlace, recovery.decide(PlayerError(decoder)))
        assertEquals(FallBack("B"), recovery.decide(PlayerError(decoder)))
    }

    @Test
    fun `Retry asks for fresh links before it plays, and replays in place once when nothing new comes`() {
        val attempts = ViewerStreamAttempts()
        assertNull("A first load plays at once", recovery(attempts).opening(0))
        val retried = recovery(attempts, loadGeneration = 1)
        assertEquals(AskForFreshLinks, retried.opening(0))
        assertEquals(RetryInPlace, retried.decide(NothingNew))
        assertEquals(FallBack("B"), retried.decide(PlayerError(badStatus)))
        assertNull(recovery(attempts, loadGeneration = 1, playing = "C").opening(0))
    }

    @Test
    fun `a stream with the same request as the one that failed is still a new player`() {
        val streams = listOf(MediaStream("https://cdn.test/e.m3u8", "Server A"), MediaStream("https://cdn.test/e.m3u8", "Server B"))
        val media = ImageRef(url = null, localPath = null, mime = "video/mp4", streams = streams)
        val load = ViewerMediaLoad(episode, 0)
        fun key(label: String): ViewerVideoKey {
            val playback = requireNotNull(media.viewerPlayback(SourceKey.HITOMI, VideoQuality.AUTO, metered = false, streamLabel = label))
            return ViewerVideoKey.of(load, media, label, playback)
        }
        assertEquals(key("Server A").playback, key("Server B").playback)
        assertNotEquals(key("Server A"), key("Server B"))
    }

    @Test
    fun `a failure says plain HTTP is blocked when it is, and the player's message otherwise`() {
        assertEquals("This stream uses plain HTTP, which is blocked.", viewerVideoFailureMessage(cleartext, "Source error"))
        assertEquals("Source error", viewerVideoFailureMessage(badStatus, "Source error"))
        assertEquals(VIEWER_VIDEO_FAILED, viewerVideoFailureMessage(unknown, null))
    }

    @Test
    fun `media without streams retries a decoder once and fails on anything else`() {
        val recovery = ViewerVideoRecovery(ViewerMediaLoad(episode, 1))
        assertNull(recovery.opening(0))
        assertEquals(Fail, recovery.decide(PlayerError(badStatus)))
        assertEquals(RetryInPlace, recovery.decide(PlayerError(decoder)))
        assertEquals(Fail, recovery.decide(PlayerError(decoder)))
    }

    private fun recovery(
        attempts: ViewerStreamAttempts,
        labels: List<String> = this.labels,
        playing: String = labels.first(),
        picked: String? = null,
        linksAgeMs: Long = 0,
        loadGeneration: Long = 0,
    ) = ViewerVideoRecovery(
        ViewerMediaLoad(episode, loadGeneration),
        ViewerEpisodeStreams(attempts, labels, picked, linksResolvedAtMs = NOW - linksAgeMs, ask = { ViewerMediaRecovery.NOTHING_NEW }, fallBack = {}),
        playing,
        loadStartedAtMs = NOW,
    )

    private companion object {
        const val NOW = 1_000_000_000L
    }
}
