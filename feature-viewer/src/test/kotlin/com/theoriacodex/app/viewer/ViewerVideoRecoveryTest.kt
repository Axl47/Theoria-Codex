package com.theoriacodex.app.viewer

import androidx.media3.common.PlaybackException
import com.theoriacodex.app.viewer.ViewerRecoveryDecision.AskForFreshLinks
import com.theoriacodex.app.viewer.ViewerRecoveryDecision.Fail
import com.theoriacodex.app.viewer.ViewerRecoveryDecision.FallBack
import com.theoriacodex.app.viewer.ViewerRecoveryDecision.RetryInPlace
import com.theoriacodex.app.viewer.ViewerRecoveryEvent.NothingNew
import com.theoriacodex.app.viewer.ViewerRecoveryEvent.PlayerError
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [ViewerVideoRecovery.decide] as a table: each row is the situation, the event, and what the player does next. */
class ViewerVideoRecoveryTest {
    private val episode = ViewerMediaKey(PostId(SourceKey.HITOMI, "episode"), 0)
    private val labels = listOf("A", "B", "C", "D", "E")
    private val badStatus = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    private val errorPage = PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
    private val decoder = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    private val unknown = PlaybackException.ERROR_CODE_UNSPECIFIED

    /** One load's situation: how it got here, then the event. */
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
        )
        rows.forEach { row ->
            val recovery = recovery(ViewerStreamAttempts(), pickedByHand = row.pickedByHand, linksAgeMs = row.linksAgeMs)
            if (row.played) recovery.onPlayed()
            assertEquals(row.name, row.expected, recovery.decide(row.event))
        }
    }

    @Test
    fun `links that expire again later in a long episode recover each time`() {
        val attempts = ViewerStreamAttempts()
        val first = recovery(attempts).apply { onPlayed() }
        assertEquals(AskForFreshLinks, first.decide(PlayerError(badStatus, positionMs = 600_000)))
        // The fresh links play on well past where the old ones died, then expire in their turn.
        val recovered = recovery(attempts).apply { onPlayed(); onProgress(600_000 + VIEWER_RECOVERED_PROGRESS_MS) }
        assertEquals(AskForFreshLinks, recovered.decide(PlayerError(errorPage, positionMs = 1_200_000)))
    }

    @Test
    fun `a stream that dies at one point asks once, then falls back, then fails with the picker`() {
        val attempts = ViewerStreamAttempts()
        val labels = listOf("A", "B")
        val first = recovery(attempts, labels).apply { onPlayed() }
        assertEquals(AskForFreshLinks, first.decide(PlayerError(badStatus, positionMs = 600_000)))
        // The fresh links resume just before the bad point and render a frame there: that is not progress.
        val recovered = recovery(attempts, labels).apply { onPlayed(); onProgress(598_000); onProgress(601_000) }
        assertEquals(FallBack("B"), recovered.decide(PlayerError(badStatus, positionMs = 601_000)))
        val fallback = recovery(attempts, labels, playing = "B").apply { onPlayed(); onProgress(601_000) }
        assertEquals(Fail, fallback.decide(PlayerError(badStatus, positionMs = 601_000)))
    }

    @Test
    fun `a lookup cancelled before it ended gives the ask back`() {
        val attempts = ViewerStreamAttempts()
        // Swiped away while the expired links were looked up: back on the page, the old links fail again and ask again.
        val first = recovery(attempts).apply { onPlayed() }
        assertEquals(AskForFreshLinks, first.decide(PlayerError(badStatus)))
        first.onAskCancelled()
        assertEquals(AskForFreshLinks, recovery(attempts).decide(PlayerError(badStatus)))
        // Left during Retry's opening lookup: the Retry still opens with the ask on return.
        val retried = ViewerStreamAttempts()
        recovery(retried, loadGeneration = 1).apply { assertEquals(AskForFreshLinks, opening(0)) }.onAskCancelled()
        assertEquals(AskForFreshLinks, recovery(retried, loadGeneration = 1).opening(0))
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
        // The replayed player, with the ask spent, moves on to the next stream when it fails.
        assertEquals(FallBack("B"), retried.decide(PlayerError(badStatus)))
        // Another player of the Retry (a stream picked meanwhile) plays at once.
        assertNull(recovery(attempts, loadGeneration = 1, playing = "C").opening(0))
    }

    @Test
    fun `a stream with the same request as the one that failed is still a new player`() {
        // Two servers offering one address under two labels.
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
        pickedByHand: Boolean = false,
        linksAgeMs: Long = 0,
        loadGeneration: Long = 0,
    ) = ViewerVideoRecovery(
        ViewerMediaLoad(episode, loadGeneration),
        ViewerEpisodeStreams(attempts, labels, pickedByHand, linksResolvedAtMs = NOW - linksAgeMs, ask = { ViewerMediaRecovery.NOTHING_NEW }, fallBack = {}),
        playing,
        loadStartedAtMs = NOW,
    )

    private companion object {
        const val NOW = 1_000_000_000L
    }
}
