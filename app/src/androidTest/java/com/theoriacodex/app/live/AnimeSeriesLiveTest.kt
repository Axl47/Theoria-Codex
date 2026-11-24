package com.theoriacodex.app.live

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.theoriacodex.app.live.AnimeViewerHand.Companion.EXTENSION_NAME
import com.theoriacodex.app.live.AnimeViewerHand.Companion.PLAYING
import com.theoriacodex.app.live.AnimeViewerHand.Companion.SERIES_QUERY
import com.theoriacodex.app.live.AnimeViewerHand.Companion.seconds
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.data.android.room.TheoriaRoomDatabase
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesId
import java.util.regex.Pattern
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimeSeriesLiveTest {
    private val hand = AnimeViewerHand()
    private val live = hand.live

    private val notes by lazy { live.context.getSharedPreferences("anime_series_live_test", Context.MODE_PRIVATE) }

    @Before
    fun requireExplicitOptIn() = live.requireOptIn()

    @Test
    fun anEpisodePlaysSwitchesStreamAndIsLeftPartway() {
        val extension = hand.argument(EXTENSION_NAME)
        live.openSeriesTab(By.text(Pattern.compile("Browse|No extensions on")))
        hand.turnOn(extension)
        browseAnime(extension)
        val title = hand.openSeries(hand.argument(SERIES_QUERY))
        val episodes = live.waitFor("the episode count", By.text(AnimeViewerHand.EPISODE_COUNT), SeriesLiveDriver.RESULTS_TIMEOUT_MS).text
            .let(AnimeViewerHand::episodeCount)
        live.report("series", "\"$title\", episodes=$episodes")
        assertTrue("The series lists enough episodes for the story", episodes >= MIN_EPISODES)

        live.tap("the button that starts watching", By.text(AnimeViewerHand.WATCH_BUTTON))
        val episode = hand.episodeOnScreen(title)
        val playing = hand.awaitPosition("playback past ${PLAYED_MS / 1000} s") { it >= PLAYED_MS }
        val later = hand.awaitPosition("playback to keep advancing") { it >= playing + PLAYED_MS }
        live.report("play", "\"$episode\" plays: ${playing.seconds()} s, then ${later.seconds()} s")
        if (live.targetIsThisApp) recordsPlaybackEvery10Seconds(title, episode)

        val (from, to) = hand.switchStream()
        val before = hand.awaitPosition("the position before the switch") { true }
        live.tap("the stream \"$to\"", By.text(to))
        live.await("the stream pill to name \"$to\"", hand::streamOnScreen) { it == to }
        val after = hand.awaitPlayback("playback on \"$to\" near ${before.seconds()} s") { it.state == PLAYING && abs(it.positionMs - before) < NEAR_MS }.positionMs
        val advancing = hand.awaitPosition("playback on \"$to\" to advance") { it >= after + PLAYED_MS }
        live.report("stream", "\"$from\" at ${before.seconds()} s became \"$to\" at ${after.seconds()} s, then ${advancing.seconds()} s")
        if (live.targetIsThisApp) recordsPlaybackOnPause(title, episode)

        val closedAt = hand.leavePlaying(episode)
        live.tap("Continue naming the episode closed partway", By.text(AnimeViewerHand.continueLabel(episode)))
        hand.assertResumesAt("close", episode, closedAt, "after closing it while it played")

        val left = hand.leavePlaying(episode)
        notes.edit().putString(TITLE, title).putString(EPISODE, episode).putLong(LEFT_AT, left).commit()
        live.report("left", "\"$episode\" at ${left.seconds()} s; the series page reads \"${AnimeViewerHand.continueLabel(episode)}\"")
    }

    @Test
    fun theEpisodeResumesAfterARestartAndPlaysOnIntoTheNext() {
        val title = checkNotNull(notes.getString(TITLE, null)) { "Run anEpisodePlaysSwitchesStreamAndIsLeftPartway first" }
        val episode = checkNotNull(notes.getString(EPISODE, null))
        val leftAt = notes.getLong(LEFT_AT, 0)
        live.openSeriesTab()
        assertEquals(title, hand.openSeries(hand.argument(SERIES_QUERY)))
        live.tap("Continue naming the episode left partway", By.text(AnimeViewerHand.continueLabel(episode)), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        assertEquals(episode, hand.episodeOnScreen(title))
        hand.assertResumesAt("resume", episode, leftAt, "after a restart")

        val pressedAt = System.currentTimeMillis()
        hand.press("Next episode", By.desc("Next episode"))
        val next = live.await("the next episode's title", { hand.episodeOnScreen(title) }) { it != episode }
        val start = hand.awaitPlayback("the next episode to play") { it.state == PLAYING }
        live.report("next", "\"$next\" after \"$episode\" by hand: playing from ${start.positionMs.seconds()} s, ${System.currentTimeMillis() - pressedAt} ms after the press")
        assertTrue("The next episode starts near its beginning", start.positionMs < NEAR_MS)

        hand.press("Next episode", By.desc("Next episode"))
        val glanced = live.await("the episode after \"$next\"", { hand.episodeOnScreen(title) }) { it != next }
        val shownAt = System.currentTimeMillis()
        hand.leaveViewer()
        val leftAfterMs = System.currentTimeMillis() - shownAt
        live.waitFor("Continue naming the episode glanced at", By.text(AnimeViewerHand.continueLabel(glanced)), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        live.report("glance", "left \"$glanced\" ${leftAfterMs} ms after it showed; the series page reads \"${AnimeViewerHand.continueLabel(glanced)}\"")
        assertTrue("Left within five seconds", leftAfterMs < GLANCE_MS)

        val expected = hand.entryAfter(glanced)
        live.tap("Continue naming the episode glanced at", By.text(AnimeViewerHand.continueLabel(glanced)))
        hand.awaitPlayback("\"$glanced\" to play") { it.state == PLAYING }
        endOfEpisode(title, glanced, expected, playingSince = System.currentTimeMillis())
        hand.leaveViewer()
        live.context.deleteSharedPreferences("anime_series_live_test")
    }

    private fun browseAnime(source: String) {
        live.tap("the Browse view", By.text(SeriesLiveDriver.BROWSE))
        val browsed = live.await("the browsed source", { hand.labels().dropWhile { it != SeriesLiveDriver.BROWSE }.getOrNull(1) }) { it != null }
        if (browsed != source) {
            live.tap("the browsed source, which opens the picker", By.text(checkNotNull(browsed)))
            live.tap("$source in the picker", By.text(source))
        }
        val chrome = setOf(POPULAR, LATEST) + TopLevelDestination.entries.map { it.label }
        val listed = live.await("$source's listing", { hand.labels().dropWhile { it != source }.drop(1).filterNot { it in chrome } }) {
            it.size >= LISTED_AT_LEAST
        }
        live.report("browse", "$source lists ${listed.size} labels")
    }

    private fun endOfEpisode(title: String, episode: String, expected: String, playingSince: Long) {
        live.viewerControl("Next episode, which the last episode lacks", By.desc("Next episode"))
        val durationMs = hand.durationOnScreen()
        hand.seekTo(durationMs)
        val nearEnd = hand.awaitPosition("the playback near its end") { it >= durationMs * 9 / 10 }
        live.report("end", "sought \"$episode\" to ${nearEnd.seconds()} s")
        var furthest = nearEnd
        val ended = hand.awaitPlayback("the episode to end") { playback ->
            val wentBack = playback.positionMs < furthest - NEAR_MS
            furthest = maxOf(furthest, playback.positionMs)
            wentBack || playback.state !in setOf(PLAYING, "BUFFERING")
        }
        val endedAt = System.currentTimeMillis()
        val after = live.await("the Viewer to move on", { hand.episodeOnScreen(title) }) { it != episode }
        live.report("end", "\"$episode\" reached ${furthest.seconds()} s; then \"$after\", $ended; the row after it reads \"$expected\"")
        assertTrue("The Viewer moves on to the next entry", hand.rowNames(expected, after))
        val playingAt = hand.awaitPlayback("\"$after\" to play") { it.state == PLAYING }.let { System.currentTimeMillis() }
        val lookups = hand.lookupsSince(playingSince).map { it - endedAt }
        live.report("prefetch", "\"$after\" played ${playingAt - endedAt} ms after the end; episode lookups at $lookups ms from the end")
        assertEquals("One episode lookup, the next entry's, since this one began playing", 1, lookups.size)
        assertTrue("The next entry was looked up before the end", lookups.single() < 0)
    }

    private fun recordsPlaybackEvery10Seconds(title: String, episode: String) {
        val changes = mutableListOf<Pair<Long, Long>>()
        var last: Long? = null
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < CADENCE_WATCH_MS) {
            val stored = storedEpisode(title, episode).position
            if (stored != last) changes += (System.currentTimeMillis() - start) to stored
            last = stored
            Thread.sleep(SeriesLiveDriver.POLL_MS)
        }
        val gaps = changes.drop(1).zipWithNext { a, b -> b.first - a.first }
        live.report("cadence", "stored positions ${changes.map { (at, ms) -> "${ms.seconds()} s at +${at.seconds()} s" }}; gaps ${gaps.map { it.seconds() }} s")
        assertTrue("Playback is recorded while it plays", gaps.size >= 2)
        assertTrue("About every ten seconds", gaps.all { it in REPORT_GAPS_MS })
    }

    private fun recordsPlaybackOnPause(title: String, episode: String) {
        hand.press("Pause", By.desc("Pause"))
        val paused = hand.awaitPlayback("the player to pause") { it.state == "PAUSED" }.positionMs
        val stored = live.await("the paused position to be stored", { storedEpisode(title, episode).position }) { abs(it - paused) < PAUSE_NEAR_MS }
        live.report("pause", "paused at ${paused.seconds()} s, stored ${stored.seconds()} s")
        hand.press("Play", By.desc("Play"))
    }

    private fun storedEpisode(title: String, episode: String): StoredSeriesEntry {
        val path = live.context.getDatabasePath(TheoriaRoomDatabase.DEFAULT_DATABASE_NAME).path
        val id = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT source, address FROM series WHERE title = ?", arrayOf(title)).use { row ->
                check(row.moveToFirst()) { "\"$title\" is not stored" }
                SeriesId(checkNotNull(SourceKey.parse(row.getString(0))), row.getString(1))
            }
        }
        return runBlocking { live.series.repository.entries(id) }.first { it.entry.name == episode }
    }

    private companion object {
        const val TITLE = "title"
        const val EPISODE = "episode"
        const val LEFT_AT = "leftAt"
        const val POPULAR = "Popular"
        const val LATEST = "Latest"
        const val LISTED_AT_LEAST = 3
        const val MIN_EPISODES = 4
        const val PLAYED_MS = 5_000L

        const val NEAR_MS = 20_000L

        const val PAUSE_NEAR_MS = 2_000L
        const val GLANCE_MS = 5_000L
        const val CADENCE_WATCH_MS = 32_000L
        val REPORT_GAPS_MS = 8_000L..13_000L
    }
}
