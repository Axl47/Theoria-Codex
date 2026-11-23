package com.theoriacodex.app.live

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.theoriacodex.app.live.AnimeViewerHand.Companion.EXTENSION_NAME
import com.theoriacodex.app.live.AnimeViewerHand.Companion.PLAYING
import com.theoriacodex.app.live.AnimeViewerHand.Companion.SERIES_QUERY
import com.theoriacodex.app.live.AnimeViewerHand.Companion.seconds
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

/**
 * Proves watching an anime from a real Aniyomi extension end to end, through the app's screen: turning the extension
 * on, the Anime filter, browsing, a series, playing an episode, switching its stream, closing it mid-episode, resuming
 * it after a restart, a short watch moving Continue, and going on to the next episode, by hand and at an episode's end.
 * It talks to the live site, so it runs only with `-e extensionSourceLive true`, with the extension installed as a
 * package (`adb install`). Nothing names the extension or the series in the code: `-e extensionName` names the
 * extension as the Extensions page lists it, and `-e seriesQuery` the search that finds a series of four or more
 * episodes.
 *
 * `-e targetPackage com.theoriacodex.acceptance` drives the minified acceptance build instead of this debug app, by
 * its screen and the player state its media session reports; with the debug app, the stored progress is read as well,
 * which shows playback is recorded about every ten seconds and on pause.
 *
 * The story crosses a restart, so run each test as its own `am instrument` call with the app stopped between them:
 * [anEpisodePlaysSwitchesStreamAndIsLeftPartway], `am force-stop <targetPackage>`, then
 * [theEpisodeResumesAfterARestartAndPlaysOnIntoTheNext].
 *
 * The report is titles, labels, counts and times only: no screenshots, and nothing about what an episode shows.
 */
@RunWith(AndroidJUnit4::class)
class AnimeSeriesLiveTest {
    private val hand = AnimeViewerHand()
    private val live = hand.live
    private val device = live.device

    /** What the first run leaves for the second, in the debug app's data, which a force-stop keeps. */
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
            .substringAfterLast(" · ").substringBefore(' ').toInt()
        live.report("series", "\"$title\", episodes=$episodes")
        assertTrue("The series lists enough episodes for the story", episodes >= MIN_EPISODES)

        live.tap("the button that starts watching", By.text(AnimeViewerHand.WATCH_BUTTON))
        val episode = hand.episodeOnScreen(title)
        val playing = hand.awaitPosition("playback past ${PLAYED_MS / 1000} s") { it >= PLAYED_MS }
        val later = hand.awaitPosition("playback to keep advancing") { it >= playing + PLAYED_MS }
        live.report("play", "\"$episode\" plays: ${playing.seconds()} s, then ${later.seconds()} s")
        if (live.targetIsThisApp) recordsPlaybackEvery10Seconds(title, episode)

        val (from, to) = switchStream()
        val before = hand.awaitPosition("the position before the switch") { true }
        live.tap("the stream \"$to\"", By.text(to))
        live.await("the stream pill to name \"$to\"", hand::streamOnScreen) { it == to }
        val after = hand.awaitPlayback("playback on \"$to\" near ${before.seconds()} s") { it.state == PLAYING && abs(it.positionMs - before) < NEAR_MS }.positionMs
        val advancing = hand.awaitPosition("playback on \"$to\" to advance") { it >= after + PLAYED_MS }
        live.report("stream", "\"$from\" at ${before.seconds()} s became \"$to\" at ${after.seconds()} s, then ${advancing.seconds()} s")
        if (live.targetIsThisApp) recordsPlaybackOnPause(title, episode)

        val closedAt = leavePlaying(episode)
        live.tap("Continue naming the episode closed partway", By.text("Continue · $episode"))
        assertResumesAt("close", episode, closedAt, "after closing it while it played")

        val left = leavePlaying(episode)
        notes.edit().putString(TITLE, title).putString(EPISODE, episode).putLong(LEFT_AT, left).commit()
        live.report("left", "\"$episode\" at ${left.seconds()} s; the series page reads \"Continue · $episode\"")
    }

    @Test
    fun theEpisodeResumesAfterARestartAndPlaysOnIntoTheNext() {
        val title = checkNotNull(notes.getString(TITLE, null)) { "Run anEpisodePlaysSwitchesStreamAndIsLeftPartway first" }
        val episode = checkNotNull(notes.getString(EPISODE, null))
        val leftAt = notes.getLong(LEFT_AT, 0)
        live.openSeriesTab()
        narrowToAnime()
        assertEquals(title, hand.openSeries(hand.argument(SERIES_QUERY)))
        live.tap("Continue naming the episode left partway", By.text("Continue · $episode"), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        assertEquals(episode, hand.episodeOnScreen(title))
        assertResumesAt("resume", episode, leftAt, "after a restart")

        // By hand from early on, the next episode is looked up when asked for.
        val pressedAt = System.currentTimeMillis()
        hand.press("Next episode", By.desc("Next episode"))
        val next = live.await("the next episode's title", { hand.episodeOnScreen(title) }) { it != episode }
        val start = hand.awaitPlayback("the next episode to play") { it.state == PLAYING }
        live.report("next", "\"$next\" after \"$episode\" by hand: playing from ${start.positionMs.seconds()} s, ${System.currentTimeMillis() - pressedAt} ms after the press")
        assertTrue("The next episode starts near its beginning", start.positionMs < NEAR_MS)

        // A glance at an episode makes it the one Continue names.
        hand.press("Next episode", By.desc("Next episode"))
        val glanced = live.await("the episode after \"$next\"", { hand.episodeOnScreen(title) }) { it != next }
        val shownAt = System.currentTimeMillis()
        hand.leaveViewer()
        val leftAfterMs = System.currentTimeMillis() - shownAt
        live.waitFor("Continue naming the episode glanced at", By.text("Continue · $glanced"), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        live.report("glance", "left \"$glanced\" ${leftAfterMs} ms after it showed; the series page reads \"Continue · $glanced\"")
        assertTrue("Left within five seconds", leftAfterMs < GLANCE_MS)

        live.tap("Continue naming the episode glanced at", By.text("Continue · $glanced"))
        hand.awaitPlayback("\"$glanced\" to play") { it.state == PLAYING }
        endOfEpisode(title, glanced)
        hand.leaveViewer()
        live.context.deleteSharedPreferences("anime_series_live_test")
    }

    /** Leaves the Viewer while [episode] plays; returns where it was, which the series page's Continue then names. */
    private fun leavePlaying(episode: String): Long {
        val at = hand.awaitPlayback("\"$episode\" playing") { it.state == PLAYING }.positionMs
        hand.leaveViewer()
        live.waitFor("Continue naming \"$episode\"", By.text("Continue · $episode"))
        return at
    }

    /** Checks [episode], just reopened, plays from within [RESUME_NEAR_MS] of [leftAt]. */
    private fun assertResumesAt(step: String, episode: String, leftAt: Long, how: String) {
        val resumed = hand.awaitPlayback("\"$episode\" to play again") { it.state == PLAYING }.positionMs
        live.report(step, "\"$episode\" left at ${leftAt / 100 / 10.0} s resumed at ${resumed / 100 / 10.0} s $how")
        assertTrue("It resumes where it was left", resumed - leftAt in -RESUME_NEAR_MS..RESUME_NEAR_MS)
    }

    /** Browses [source]; its listing fills the tab below its Popular, Latest and Filter row. */
    private fun browseAnime(source: String) {
        narrowToAnime()
        val chip = live.waitFor("$source's chip", By.checkable(true).hasDescendant(By.text(source)))
        if (!chip.isChecked) chip.click()
        val listed = live.await("$source's listing", { labels().dropWhile { it != "Filter" }.drop(1) }) { it.size >= LISTED_AT_LEAST }
        live.report("browse", "$source lists ${listed.size} labels")
    }

    /** Taps the Anime filter, which the tab offers only when the user has series of both kinds. */
    private fun narrowToAnime() {
        if (device.wait(Until.hasObject(By.text(ANIME).clickable(true)), SeriesLiveDriver.SHORT_WAIT_MS) == true) {
            live.tap("the Anime filter", By.text(ANIME).clickable(true))
            live.report("filter", "narrowed to Anime")
        } else {
            live.report("filter", "no kind filter: only anime is on")
        }
    }

    /** Opens the stream list; returns the stream playing and another one to switch to. */
    private fun switchStream(): Pair<String, String> {
        val current = hand.streamOnScreen()
        val before = labels()
        hand.press("the stream pill", By.desc(AnimeViewerHand.STREAM))
        val listed = live.await("the stream list", { labels() - before.toSet() - current }) { it.isNotEmpty() }
        live.report("streams", "playing \"$current\"; listed $listed")
        return current to (listed.firstOrNull { it != current } ?: error("The episode offers one stream: $listed"))
    }

    /**
     * Takes [episode] past its watched point through the timeline and lets it end, then checks it never starts again:
     * the Viewer either moves on to the next episode or, on the last one, stays at its end. Moving on is timed from the
     * end to the next episode playing, and the next episode's lookup must have begun before the end.
     */
    private fun endOfEpisode(title: String, episode: String) {
        val track = live.viewerControl("the timeline", By.clazz("android.widget.ProgressBar"))
        val draggedAt = System.currentTimeMillis()
        val bounds = track.visibleBounds
        device.drag(bounds.left + 4, bounds.centerY(), bounds.right - 2, bounds.centerY(), DRAG_STEPS)
        val nearEnd = hand.awaitPosition("the playback near its end") { true }
        live.report("end", "dragged \"$episode\" to ${nearEnd.seconds()} s")
        // The end shows as the position going back (the next episode, or the same one again) or the player stopping.
        var furthest = nearEnd
        val ended = hand.awaitPlayback("the episode to end") { playback ->
            val wentBack = playback.positionMs < furthest - NEAR_MS
            furthest = maxOf(furthest, playback.positionMs)
            wentBack || playback.state !in setOf(PLAYING, "BUFFERING")
        }
        val endedAt = System.currentTimeMillis()
        val after = hand.episodeOnScreen(title)
        live.report("end", "\"$episode\" reached ${furthest.seconds()} s; then \"$after\", $ended")
        assertTrue("The episode did not start again", after != episode || ended.positionMs >= nearEnd)
        if (after == episode) return
        val playingAt = hand.awaitPlayback("\"$after\" to play") { it.state == PLAYING }.let { System.currentTimeMillis() }
        val lookups = hand.lookupsSince(draggedAt).map { it - endedAt }
        live.report("prefetch", "\"$after\" played ${playingAt - endedAt} ms after the end; episode lookups at $lookups ms from the end")
        assertTrue("The next episode was looked up before the end", lookups.any { it < 0 })
    }

    /** Reads the stored progress for half a minute of playback and checks it is written about every ten seconds. */
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
        // The first value was there before the watch began, so only the writes after it measure the gaps.
        val gaps = changes.drop(1).zipWithNext { a, b -> b.first - a.first }
        live.report("cadence", "stored positions ${changes.map { (at, ms) -> "${ms.seconds()} s at +${at.seconds()} s" }}; gaps ${gaps.map { it.seconds() }} s")
        assertTrue("Playback is recorded while it plays", gaps.size >= 2)
        assertTrue("About every ten seconds", gaps.all { it in REPORT_GAPS_MS })
    }

    /** Pauses playback and checks the position is stored at once, then plays on. */
    private fun recordsPlaybackOnPause(title: String, episode: String) {
        hand.press("Pause", By.desc("Pause"))
        val paused = hand.awaitPlayback("the player to pause") { it.state == "PAUSED" }.positionMs
        val stored = live.await("the paused position to be stored", { storedEpisode(title, episode).position }) { abs(it - paused) < PAUSE_NEAR_MS }
        live.report("pause", "paused at ${paused.seconds()} s, stored ${stored.seconds()} s")
        hand.press("Play", By.desc("Play"))
    }

    /** What the debug app stored for [episode] of the series titled [title]; read only. */
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

    /** Every label of the app on screen, in screen order. */
    private fun labels(): List<String> = device.findObjects(By.pkg(live.targetPackage))
        .mapNotNull { runCatching { it.text }.getOrNull() }
        .filter(String::isNotBlank)

    private companion object {
        const val TITLE = "title"
        const val EPISODE = "episode"
        const val LEFT_AT = "leftAt"
        const val ANIME = "Anime"
        const val LISTED_AT_LEAST = 3
        const val MIN_EPISODES = 4
        const val PLAYED_MS = 5_000L

        /** How far a stream switch may land from where playback was: segments start every few seconds. */
        const val NEAR_MS = 20_000L

        /** How far a reopened episode may resume from where it was left: progress is saved as it is left. */
        const val RESUME_NEAR_MS = 2_500L
        const val PAUSE_NEAR_MS = 2_000L
        const val GLANCE_MS = 5_000L
        const val CADENCE_WATCH_MS = 32_000L
        val REPORT_GAPS_MS = 8_000L..13_000L
        const val DRAG_STEPS = 40
    }
}
