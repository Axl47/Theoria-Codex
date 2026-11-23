package com.theoriacodex.app.live

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
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
 * on, the Anime filter, browsing, a series, playing an episode, switching its stream, resuming it after a restart and
 * going on to the next one. It talks to the live site, so it runs only with `-e extensionSourceLive true`, with the
 * extension installed as a package (`adb install`). Nothing names the extension or the series in the code:
 * `-e extensionName` names the extension as the Extensions page lists it, and `-e seriesQuery` the search that finds a
 * series of two or more episodes.
 *
 * `-e targetPackage com.theoriacodex.acceptance` drives the minified acceptance build instead of this debug app, by
 * its screen alone; with the debug app, the stored progress is read as well, which shows how often playback is
 * recorded.
 *
 * The story crosses a restart, so run each test as its own `am instrument` call with the app stopped between them:
 * [anEpisodePlaysSwitchesStreamAndIsLeftPartway], `am force-stop <targetPackage>`, then
 * [theEpisodeResumesAfterARestartAndPlaysOnIntoTheNext].
 *
 * The report is titles, labels, counts and times only: no screenshots, and nothing about what an episode shows.
 */
@RunWith(AndroidJUnit4::class)
class AnimeSeriesLiveTest {
    private val live = SeriesLiveDriver()
    private val device = live.device
    private val arguments = InstrumentationRegistry.getArguments()

    /** What the first run leaves for the second, in this test app's own data, which a stop of the target keeps. */
    private val notes by lazy { live.context.getSharedPreferences("anime_series_live_test", Context.MODE_PRIVATE) }

    @Before
    fun requireExplicitOptIn() = live.requireOptIn()

    @Test
    fun anEpisodePlaysSwitchesStreamAndIsLeftPartway() {
        val extension = argument(EXTENSION_NAME)
        live.openSeriesTab(By.text(Pattern.compile("Browse|No extensions on")))
        turnOn(extension)
        browseAnime(arguments.getString(SOURCE_NAME) ?: extension)
        val title = openSeries(argument(SERIES_QUERY))
        val episodes = live.waitFor("the episode count", By.text(EPISODE_COUNT), SeriesLiveDriver.RESULTS_TIMEOUT_MS).text
            .substringAfterLast(" · ").substringBefore(' ').toInt()
        live.report("series", "\"$title\", episodes=$episodes")
        assertTrue("The series lists more than one episode", episodes > 1)

        live.tap("the button that starts watching", By.text(Pattern.compile("Start|Continue · .*")))
        val episode = episodeOnScreen(title)
        val playing = awaitPosition("playback past ${PLAYED_MS / 1000} s") { it >= PLAYED_MS }
        val later = awaitPosition("playback to keep advancing") { it > playing }
        live.report("play", "\"$episode\" plays: ${playing.seconds()} s, then ${later.seconds()} s")
        if (live.targetIsThisApp) recordsPlaybackEvery10Seconds(title, episode)

        val (from, to) = switchStream()
        val before = awaitPosition("the position before the switch") { true }
        live.tap("the stream \"$to\"", By.text(to))
        live.viewerControl("the stream pill naming \"$to\"", By.desc(STREAM).hasDescendant(By.text(to)), PLAYBACK_TIMEOUT_MS)
        val after = awaitPosition("playback on \"$to\" near ${before.seconds()} s") { abs(it - before) < NEAR_MS && it > PLAYED_MS }
        val advancing = awaitPosition("playback on \"$to\" to advance") { it > after }
        live.report("stream", "\"$from\" at ${before.seconds()} s became \"$to\" at ${after.seconds()} s, then ${advancing.seconds()} s")

        if (live.targetIsThisApp) recordsPlaybackOnPause(title, episode)
        val left = awaitPosition("the position the episode is left at") { true }
        leaveViewer()
        live.waitFor("Continue naming the episode left partway", By.text("Continue · $episode"))
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
        assertEquals(title, openSeries(argument(SERIES_QUERY)))
        live.tap("Continue naming the episode left partway", By.text("Continue · $episode"), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        assertEquals(episode, episodeOnScreen(title))
        val resumed = awaitPosition("playback from near ${leftAt.seconds()} s") { it > PLAYED_MS }
        live.report("resume", "\"$episode\" left at ${leftAt.seconds()} s resumed at ${resumed.seconds()} s after a restart")
        assertTrue("It resumes near where it was left", resumed >= leftAt - NEAR_MS)

        live.viewerControl("Next episode", By.desc("Next episode")).click()
        val next = live.await("the next episode's title", { episodeOnScreen(title) }) { it != episode }
        val start = awaitPosition("the next episode to play from its start") { it > 0 }
        live.report("next", "\"$next\" after \"$episode\", from ${start.seconds()} s")
        assertTrue("The next episode starts near its beginning", start < NEAR_MS)

        endOfEpisode(title, next)
        leaveViewer()
        live.context.deleteSharedPreferences("anime_series_live_test")
    }

    /** Turns [extension] on in the Extensions page unless it is on already, accepting the trust question. */
    private fun turnOn(extension: String) {
        live.tap("the Extensions button", By.desc("Extensions"))
        val switch = By.checkable(true).hasDescendant(By.desc(extension))
        if (live.waitFor("$extension's switch", switch).isChecked) {
            live.report("extension", "$extension was on already")
        } else {
            live.tap("$extension's switch", switch)
            live.tap("Turn on", By.text("Turn on"))
            live.waitFor("the switch to read as on", switch.checked(true), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
            live.report("extension", "turned $extension on")
        }
        device.pressBack()
        live.waitFor("the Series tab's sources", By.text("Browse"), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
    }

    /**
     * Browses [source] under the Anime filter; its listing fills the tab below its Popular, Latest and Filter row. The
     * series page's episode count then shows the source is anime.
     */
    private fun browseAnime(source: String) {
        narrowToAnime()
        val chip = live.waitFor("$source's chip", By.checkable(true).hasDescendant(By.text(source)))
        if (!chip.isChecked) chip.click()
        val listed = live.await("$source's listing", { labels().dropWhile { it != "Filter" }.drop(1) }) { it.size >= LISTED_AT_LEAST }
        live.report("browse", "$source lists ${listed.size} labels")
    }

    /**
     * Taps the Anime filter. The tab offers it only when the user has series of both kinds, so with anime alone it is
     * reported missing instead.
     */
    private fun narrowToAnime() {
        if (device.wait(Until.hasObject(By.text(ANIME).clickable(true)), SeriesLiveDriver.SHORT_WAIT_MS) == true) {
            live.tap("the Anime filter", By.text(ANIME).clickable(true))
            live.report("filter", "narrowed to Anime")
        } else {
            live.report("filter", "no kind filter: only anime is on")
        }
    }

    /** Searches for [query] and opens the first series whose title holds it; returns that title. */
    private fun openSeries(query: String): String {
        val field = live.waitFor("the search field", By.clazz("android.widget.EditText"))
        field.text = query
        field.click()
        device.pressEnter()
        val matching = By.text(Pattern.compile("(?i).*${Pattern.quote(query)}.*"))
        val result = live.await("a series titled like \"$query\"", { device.findObjects(matching).firstOrNull { it.className != EDIT_TEXT } }) { it != null }
        val title = checkNotNull(result).text
        result.click()
        return title
    }

    /** The episode the Viewer's title names, read as "series · episode". */
    private fun episodeOnScreen(title: String): String =
        live.viewerControl("the episode's name as the Viewer's title", By.textStartsWith("$title · "), PLAYBACK_TIMEOUT_MS).text.removePrefix("$title · ")

    /** Opens the stream list; returns the stream playing and another one to switch to. */
    private fun switchStream(): Pair<String, String> {
        val pill = live.viewerControl("the stream pill", By.desc(STREAM))
        val current = checkNotNull(pill.text ?: pill.findObject(By.textStartsWith(""))?.text) { "The stream pill names no stream" }
        val before = labels()
        pill.click()
        val listed = live.await("the stream list", { labels() - before }) { it.isNotEmpty() }
        live.report("streams", "playing \"$current\"; listed $listed")
        return current to (listed.firstOrNull { it != current } ?: error("The episode offers one stream: $listed"))
    }

    /**
     * Takes the playback to its last seconds through the timeline, then checks it never starts again: the Viewer either
     * moves on to the episode after [episode] or, on the last one, stays at its end.
     */
    private fun endOfEpisode(title: String, episode: String) {
        val track = live.viewerControl("the timeline", By.clazz("android.widget.ProgressBar"))
        val bounds = track.visibleBounds
        device.drag(bounds.left + 4, bounds.centerY(), bounds.right - 2, bounds.centerY(), DRAG_STEPS)
        val nearEnd = awaitPosition("the playback near its end") { true }
        live.report("end", "dragged \"$episode\" to ${nearEnd.seconds()} s")
        Thread.sleep(END_WAIT_MS)
        val after = episodeOnScreen(title)
        val position = awaitPosition("the position after the end") { true }
        live.report("end", "after the end: \"$after\" at ${position.seconds()} s")
        assertTrue("The episode did not start again", after != episode || position >= nearEnd)
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
        val gaps = changes.zipWithNext { a, b -> b.first - a.first }
        live.report("cadence", "stored positions ${changes.map { (at, ms) -> "${ms.seconds()} s at +${at.seconds()} s" }}; gaps ${gaps.map { it.seconds() }} s")
        assertTrue("Playback is recorded while it plays", changes.size >= 2)
        assertTrue("About every ten seconds", gaps.all { it in REPORT_GAPS_MS })
    }

    /** Pauses playback and checks the position is stored at once, then plays on. */
    private fun recordsPlaybackOnPause(title: String, episode: String) {
        live.viewerControl("Pause", By.desc("Pause")).click()
        val paused = awaitPosition("the paused position") { true }
        val stored = live.await("the paused position to be stored", { storedEpisode(title, episode).position }) { abs(it - paused) < PAUSE_NEAR_MS }
        live.report("pause", "paused at ${paused.seconds()} s, stored ${stored.seconds()} s")
        live.viewerControl("Play", By.desc("Play")).click()
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

    /**
     * The playback position the Viewer's timeline shows, once it satisfies [done]. The timeline shows while the
     * controls do, so each read brings them back.
     */
    private fun awaitPosition(what: String, done: (Long) -> Boolean): Long {
        val deadline = System.currentTimeMillis() + PLAYBACK_TIMEOUT_MS
        var last: Long? = null
        while (System.currentTimeMillis() < deadline) {
            val text = runCatching { live.viewerControl("the playback time", TIME, CONTROLS_MS).text }.getOrNull()
            last = text?.let(::positionMs) ?: last
            if (last != null && done(last)) return last
            Thread.sleep(POSITION_POLL_MS)
        }
        error("Timed out waiting for $what; last position: ${last?.seconds()} s; on screen: ${live.screenText()}")
    }

    /** Leaves the Viewer for the series page. */
    private fun leaveViewer() {
        device.pressBack()
        if (device.wait(Until.hasObject(By.text(EPISODE_COUNT)), SeriesLiveDriver.SHORT_WAIT_MS) != true) device.pressBack()
    }

    /** Every label of the app on screen, in screen order. */
    private fun labels(): List<String> = device.findObjects(By.pkg(live.targetPackage))
        .mapNotNull { runCatching { it.text }.getOrNull() }
        .filter(String::isNotBlank)

    private fun argument(name: String): String = checkNotNull(arguments.getString(name)) { "Pass -e $name" }

    private fun Long.seconds() = this / 1000

    private companion object {
        const val EXTENSION_NAME = "extensionName"
        const val SOURCE_NAME = "sourceName"
        const val SERIES_QUERY = "seriesQuery"
        const val TITLE = "title"
        const val EPISODE = "episode"
        const val LEFT_AT = "leftAt"
        const val STREAM = "Stream"
        const val ANIME = "Anime"
        const val EDIT_TEXT = "android.widget.EditText"
        val EPISODE_COUNT: Pattern = Pattern.compile("(.+ · )?\\d+ episodes?")
        /** The timeline's time: "2:03 / 24:00" in portrait; in landscape the position and length stand apart, position first. */
        val TIME: BySelector = By.text(Pattern.compile("(\\d+:)?\\d+:\\d\\d( / (\\d+:)?\\d+:\\d\\d)?"))
        const val LISTED_AT_LEAST = 3
        const val PLAYED_MS = 5_000L

        /** How far a switch or a resume may land from where playback was: segments start every few seconds. */
        const val NEAR_MS = 20_000L
        const val PAUSE_NEAR_MS = 2_000L

        /** An episode's lookup can take about 35 s for some extensions, and its stream must then buffer. */
        const val PLAYBACK_TIMEOUT_MS = 90_000L
        const val CONTROLS_MS = 5_000L
        const val POSITION_POLL_MS = 1_000L
        const val CADENCE_WATCH_MS = 32_000L
        val REPORT_GAPS_MS = 8_000L..13_000L
        const val DRAG_STEPS = 40
        const val END_WAIT_MS = 15_000L

        /** "1:02:03 / 1:20:00", "2:03 / 24:00" or "2:03": the position, before any slash. */
        fun positionMs(time: String): Long =
            time.substringBefore(" / ").split(':').fold(0L) { total, part -> total * 60 + part.trim().toLong() } * 1000
    }
}
