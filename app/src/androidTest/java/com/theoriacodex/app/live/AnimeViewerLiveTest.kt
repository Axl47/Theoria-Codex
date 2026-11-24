package com.theoriacodex.app.live

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.theoriacodex.app.live.AnimeViewerHand.Companion.EXTENSION_NAME
import com.theoriacodex.app.live.AnimeViewerHand.Companion.PLAYING
import com.theoriacodex.app.live.AnimeViewerHand.Companion.SERIES_QUERY
import com.theoriacodex.app.live.AnimeViewerHand.Companion.seconds
import com.theoriacodex.app.live.AnimeViewerHand.Companion.tenths
import com.theoriacodex.app.ui.TopLevelDestination
import java.util.regex.Pattern
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Single checks around watching anime from a real Aniyomi extension, each its own `am instrument` call, with the same
 * arguments as [AnimeSeriesLiveTest] (`-e extensionSourceLive true`, `-e extensionName`, `-e seriesQuery`, and
 * `-e targetPackage` for the acceptance build). The report is titles, labels, counts and times only.
 */
@RunWith(AndroidJUnit4::class)
class AnimeViewerLiveTest {
    private val hand = AnimeViewerHand()
    private val live = hand.live
    private val device = live.device

    @Before
    fun requireExplicitOptIn() = live.requireOptIn()

    /** An episode of the series `-e seriesQuery` finds plays, and its media session ends with the Viewer. */
    @Test
    fun anEpisodePlays() {
        val (_, episode) = watch()
        val playing = hand.awaitPosition("playback past ${PLAYED_MS / 1000} s") { it >= PLAYED_MS }
        live.report("play", "\"$episode\" plays at ${playing.seconds()} s on \"${hand.streamOnScreen()}\"")
        hand.leaveViewer()
        // The system's media controls go with the Viewer.
        val goneAfter = System.currentTimeMillis()
        live.await("the media session to end", hand::sessionActive) { !it }
        live.report("session", "no active media session ${System.currentTimeMillis() - goneAfter} ms after leaving the Viewer")
    }

    /**
     * An episode whose stream carries side-loaded subtitles shows them without being asked, and turning them off holds
     * for the next episode in the same session.
     */
    @Test
    fun sideLoadedSubtitlesAreOnUntilTurnedOff() {
        val (title, episode) = watch()
        hand.awaitPlayback("\"$episode\" to play") { it.state == PLAYING }
        val shown = hand.trackOnScreen(SUBTITLES)
        live.report("subtitles", "\"$episode\" shows \"$shown\" untouched")
        assertNotEquals("Subtitles are on without a tap", OFF, shown)

        hand.press(SUBTITLES, By.desc(SUBTITLES))
        live.tap("Off", By.text(OFF))
        live.await("the subtitles to read Off", { hand.trackOnScreen(SUBTITLES) }) { it == OFF }
        hand.press("Next episode", By.desc("Next episode"))
        val next = live.await("the next episode's title", { hand.episodeOnScreen(title) }) { it != episode }
        hand.awaitPlayback("\"$next\" to play") { it.state == PLAYING }
        val after = hand.trackOnScreen(SUBTITLES)
        live.report("subtitles", "turned off on \"$episode\"; \"$next\" shows \"$after\"")
        assertEquals("Off holds for the next episode", OFF, after)
        hand.leaveViewer()
    }

    /** An episode started over from its sheet and left partway resumes where it was left, not at its start. */
    @Test
    fun aRestartedEpisodeResumesWhereItWasLeft() {
        val (_, episode) = watch()
        val before = hand.awaitPosition("playback past ${RESTART_FROM_MS.seconds()} s") { it >= RESTART_FROM_MS }
        hand.press("Info", By.desc(Pattern.compile("Info.*")))
        live.tap("Start over", By.desc(START_OVER))
        val restarted = hand.awaitPosition("\"$episode\" to start over") { it < PLAYED_MS }
        hand.awaitPosition("playback past ${PARTWAY_MS.seconds()} s") { it >= PARTWAY_MS }
        live.report("restart", "\"$episode\" at ${before.seconds()} s started over at ${restarted.seconds()} s")
        val left = hand.leavePlaying(episode)
        live.tap("Continue naming the episode", By.text(AnimeViewerHand.continueLabel(episode)))
        hand.assertResumesAt("restart", episode, left, "after starting it over")
        hand.leaveViewer()
    }

    /**
     * A stream switched in an episode's last seconds takes over where the last one stopped: choosing a stream plays, so
     * the episode plays out its end and the Viewer moves on, instead of starting the episode again.
     */
    @Test
    fun aStreamSwitchedInTheLastSecondsTakesOverThere() {
        val (title, episode) = watch()
        hand.awaitPlayback("\"$episode\" to play") { it.state == PLAYING }
        hand.press("Pause", By.desc("Pause"))
        hand.awaitPlayback("the player to pause") { it.state == PAUSED }
        val durationMs = hand.durationOnScreen()
        hand.seekTo(durationMs - END_SEEK_MS)
        val before = hand.awaitPlayback("the seek into the last seconds") { it.state == PAUSED && it.positionMs >= durationMs - END_MARGIN_MS }.positionMs
        val (from, to) = hand.switchStream()
        live.tap("the stream \"$to\"", By.text(to))
        val after = live.await("the Viewer to move on", { hand.episodeOnScreen(title) }) { it != episode }
        val start = hand.awaitPlayback("\"$after\" to play") { it.state == PLAYING }.positionMs
        live.report("switch", "\"$from\" at ${before.tenths()} s of ${durationMs.seconds()} s became \"$to\", which played out the end; then \"$after\" from ${start.seconds()} s")
        hand.leaveViewer()
    }

    /** A seek made while paused is where the episode reopens after the app is stopped; the acceptance build only. */
    @Test
    fun aSeekWhilePausedSurvivesAForceStop() {
        assumeFalse("Stopping the app would stop this test", live.targetIsThisApp)
        val (title, episode) = watch()
        val playing = hand.awaitPosition("playback past ${PLAYED_MS.seconds()} s") { it >= PLAYED_MS }
        hand.press("Pause", By.desc("Pause"))
        hand.awaitPlayback("the player to pause") { it.state == PAUSED }
        val target = minOf(playing + SEEK_BY_MS, hand.durationOnScreen() / 2)
        hand.seekTo(target)
        val sought = hand.awaitPlayback("the seek to ${target.seconds()} s") { it.state == PAUSED && abs(it.positionMs - target) < SEEK_NEAR_MS }.positionMs
        // The seek's report is written as it lands; a moment later the app is gone without leaving the Viewer.
        Thread.sleep(SETTLE_MS)
        device.executeShellCommand("am force-stop ${live.targetPackage}")
        live.report("seek", "paused \"$episode\" at ${playing.tenths()} s, sought to ${sought.tenths()} s, then stopped the app")
        live.openSeriesTab()
        assertEquals(title, hand.openSeries(hand.argument(SERIES_QUERY)))
        live.tap("Continue naming the episode", By.text(AnimeViewerHand.continueLabel(episode)), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        hand.assertResumesAt("seek", episode, sought, "after seeking while paused and a force-stop")
        hand.leaveViewer()
    }

    /**
     * From the series page, the episode Continue names, marked watched, gives way to another, and marked unwatched, reads
     * so again. Marking the episodes before it watched can be undone. An anime offers nothing to download. Needs an
     * episode of the series watched partway first.
     */
    @Test
    fun anEpisodeIsMarkedWatchedFromTheSeriesPage() {
        live.openSeriesTab(By.text(Pattern.compile("Browse|No extensions on")))
        hand.turnOn(hand.argument(EXTENSION_NAME))
        live.openSeriesTab()
        hand.openSeries(hand.argument(SERIES_QUERY))
        val episode = continuing()
        assertNull("An anime offers no download", device.findObject(By.desc(DOWNLOAD)))

        val actions = entryActions(episode)
        live.report("actions", "\"$episode\" offers $actions")
        assertTrue("An episode cannot be downloaded", actions.none { DOWNLOAD in it })
        live.tap("Mark as watched", By.text("Mark as watched"))
        val watched = live.await("\"$episode\" to read as watched", { hand.rowDetail(hand.entryRow(episode).first) }) { WATCHED in it }
        hand.backToTop()
        val next = continuing()
        live.report("watched", "\"$episode\" reads \"$watched\"; Continue names \"$next\"")
        assertNotEquals("Continue moves past a watched episode", episode, next)

        entryActions(episode)
        live.tap("Mark as unwatched", By.text("Mark as unwatched"))
        val unwatched = live.await("\"$episode\" to read as unwatched", { hand.rowDetail(hand.entryRow(episode).first) }) { WATCHED !in it }
        hand.backToTop()
        // Unwatching the episode opened last forgets it as such, and its position: Continue falls back to the furthest
        // episode started, which depends on what was watched before.
        live.report("unwatched", "\"$episode\" reads \"$unwatched\"; Continue names \"${continuing()}\"")

        // Marking the episodes before it watched offers an undo. One of them still unwatched shows both: it is listed
        // below the episode, since the list runs newest first.
        hand.entryRow(episode)
        val (earlier, before) = hand.shownRows().dropWhile { it.first != episode }.drop(1).firstOrNull { WATCHED !in it.second }
            ?: error("No unwatched episode is listed below \"$episode\"")
        val detail = { hand.shownRows().firstOrNull { it.first == earlier }?.second }
        assertTrue("Marking the episodes before it is offered", MARK_PREVIOUS in entryActions(episode))
        live.tap(MARK_PREVIOUS, By.text(MARK_PREVIOUS))
        val marked = live.await("\"$earlier\" to read as watched", detail) { it != null && WATCHED in it }
        live.tap("Undo", By.text("Undo"))
        val undone = live.await("\"$earlier\" to read as it did", detail) { it == before }
        live.report("undo", "\"$earlier\" read \"$before\", \"$marked\" once the episodes before were marked, then \"$undone\" after Undo")
        hand.backToTop()
    }

    /** A followed anime is in Following when the app opens cold; the acceptance build only. */
    @Test
    fun aFollowedAnimeIsOnTheShelfOnAColdOpen() {
        assumeFalse("Stopping the app would stop this test", live.targetIsThisApp)
        live.openSeriesTab(By.text(Pattern.compile("Browse|No extensions on")))
        hand.turnOn(hand.argument(EXTENSION_NAME))
        live.openSeriesTab()
        val title = hand.openSeries(hand.argument(SERIES_QUERY))
        if (device.wait(Until.hasObject(By.text(FOLLOW)), SeriesLiveDriver.SHORT_WAIT_MS) == true) live.tap("Follow", By.text(FOLLOW))
        live.waitFor("the series to read as followed", By.text(FOLLOWING))
        device.executeShellCommand("am force-stop ${live.targetPackage}")
        // No view is chosen: with a series followed, the tab opens on Following.
        live.openSeriesTab(By.text(title), view = null)
        val shelf = live.waitFor("\"$title\" in Following", By.text(title)).visibleBounds
        live.report("shelf", "after a cold open, Following lists \"$title\" at $shelf")
    }

    /** The episode the series page's Continue names. */
    private fun continuing(): String {
        val button = live.waitFor("the button that starts watching", By.text(AnimeViewerHand.WATCH_BUTTON), SeriesLiveDriver.RESULTS_TIMEOUT_MS).text
        return button.removePrefix(AnimeViewerHand.continueLabel("")).takeIf { it != button }
            ?: error("Watch an episode of the series first: the page reads \"$button\"")
    }

    /** Long-presses [episode]'s row on the series page; returns the actions its menu offers. */
    private fun entryActions(episode: String): List<String> {
        val before = hand.labels().toSet()
        hand.entryRow(episode).first.longClick()
        return live.await("\"$episode\"'s actions", { hand.labels() - before }) { it.isNotEmpty() }
    }

    /** After some watching, the Stats screen splits out time on anime. */
    @Test
    fun statsShowTimeOnAnime() {
        live.openSeriesTab()
        live.tap("the Library tab", By.desc(TopLevelDestination.Library.label))
        live.tap("Settings", By.desc("Settings"))
        live.tap("Your stats", By.text("Your stats"))
        // The time split sits below the fold; each push looks for it afresh, as the screen settles while it loads.
        val x = device.displayWidth / 2
        repeat(STATS_PUSHES) {
            if (device.wait(Until.hasObject(By.text(ANIME)), SeriesLiveDriver.SHORT_WAIT_MS) == true) return@repeat
            device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 3, SWIPE_STEPS)
        }
        // Each segment's label is followed by its time.
        val labels = live.waitFor("the Anime segment", By.text(ANIME)).parent.children.mapNotNull { it.text }
        val time = labels[labels.indexOf(ANIME) + 1]
        live.report("stats", "Anime segment: \"$time\"")
        assertTrue("Time on anime shows", time.any(Char::isDigit))
    }

    /** An extension the app cannot use is listed in the Extensions page with why. */
    @Test
    fun aRefusedExtensionSaysWhy() {
        val extension = hand.argument(EXTENSION_NAME)
        live.openSeriesTab(By.text(Pattern.compile("Browse|No extensions on")))
        live.tap("the Extensions button", By.desc("Extensions"))
        val row = live.waitFor("$extension's row", By.text(extension)).parent
        val summary = row.children.mapNotNull { it.text }.first { it != extension }
        live.report("refused", "$extension: \"$summary\"")
        assertTrue("It says why", summary.contains(" · "))
        device.pressBack()
    }

    /** Turns the extension on and starts or continues the series `-e seriesQuery` finds. */
    private fun watch(): Pair<String, String> {
        live.openSeriesTab(By.text(Pattern.compile("Browse|No extensions on")))
        hand.turnOn(hand.argument(EXTENSION_NAME))
        return hand.startWatching(hand.argument(SERIES_QUERY))
    }

    private companion object {
        const val SUBTITLES = "Subtitles"
        const val OFF = "Off"
        const val ANIME = "Anime"
        const val PLAYED_MS = 5_000L
        const val START_OVER = "Start over"
        const val DOWNLOAD = "Download"
        const val WATCHED = "Watched"
        const val MARK_PREVIOUS = "Mark previous as watched"
        const val FOLLOW = "Follow"
        const val FOLLOWING = "Following"
        const val PAUSED = "PAUSED"
        const val RESTART_FROM_MS = 20_000L
        const val PARTWAY_MS = 10_000L

        /** Where the near-the-end switch aims, before the end, and how near the end the seek must land to count. */
        const val END_SEEK_MS = 2_000L
        const val END_MARGIN_MS = 5_000L
        const val SEEK_BY_MS = 60_000L

        /** How far a seek may land from where the timeline was let go: a stream seeks to its nearest key frame. */
        const val SEEK_NEAR_MS = 10_000L

        /** How long a seek's report takes to be written before the app is stopped. */
        const val SETTLE_MS = 3_000L
        const val STATS_PUSHES = 6
        const val SWIPE_STEPS = 20
    }
}
