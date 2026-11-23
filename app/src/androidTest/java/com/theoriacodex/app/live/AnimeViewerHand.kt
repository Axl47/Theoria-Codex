package com.theoriacodex.app.live

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertTrue

/**
 * The hand the anime live tests share, on top of [SeriesLiveDriver]: it turns an extension on, opens a series, and
 * watches through the Viewer by its screen and by the player state the target's media session reports to the system,
 * so it works on the minified acceptance build as well as on this debug app.
 */
internal class AnimeViewerHand(val live: SeriesLiveDriver = SeriesLiveDriver()) {
    val device = live.device
    private val arguments = InstrumentationRegistry.getArguments()

    fun argument(name: String): String = checkNotNull(arguments.getString(name)) { "Pass -e $name" }

    /** Turns [extension] on in the Extensions page unless it is on already, accepting the trust question. */
    fun turnOn(extension: String) {
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

    /** Searches the Series tab for [query] and opens the first series whose title holds it; returns that title. */
    fun openSeries(query: String): String {
        val field = live.waitFor("the search field", By.clazz(EDIT_TEXT))
        field.text = query
        field.click()
        device.pressEnter()
        val matching = By.text(Pattern.compile("(?i).*${Pattern.quote(query)}.*")).clazz(NOT_EDIT_TEXT)
        val result = live.waitFor("a series titled like \"$query\"", matching, SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        val title = result.text
        result.click()
        return title
    }

    /** Opens the series [query] finds from a fresh Series tab and starts or continues it; returns its title and episode. */
    fun startWatching(query: String): Pair<String, String> {
        live.openSeriesTab()
        val title = openSeries(query)
        live.tap("the button that starts watching", By.text(WATCH_BUTTON), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        return title to episodeOnScreen(title)
    }

    /** The episode the Viewer's title names, read as "series · episode"; a title replaced as it is read is read again. */
    fun episodeOnScreen(title: String): String = fresh {
        live.viewerControl("the episode's name as the Viewer's title", By.textStartsWith("$title · "), PLAYBACK_TIMEOUT_MS).text.removePrefix("$title · ")
    }

    /** The stream the Viewer's pill names: the text of its one node, described "Stream". */
    fun streamOnScreen(): String {
        val node = fresh { live.viewerControl("the stream pill", By.desc(STREAM)).accessibilityNodeInfo }
        return checkNotNull(node.text?.toString()) { "The stream pill names no stream" }
    }

    /** The track the Viewer's [description] button ("Subtitles" or "Audio") names as its state. */
    fun trackOnScreen(description: String): String {
        val node = fresh { live.viewerControl("the $description button", By.desc(description)).accessibilityNodeInfo }
        return checkNotNull((node.stateDescription ?: node.parent?.stateDescription)?.toString()) { "$description names no track" }
    }

    /**
     * Presses one of the Viewer's controls by an accessibility click on the node that takes it, which the controls
     * hiding a moment later cannot swallow as they can a tap. A click refused because they hid as it arrived was not
     * delivered, so it is tried again.
     */
    fun press(what: String, selector: BySelector) {
        repeat(PRESS_TRIES) {
            val node = fresh { live.viewerControl(what, selector).accessibilityNodeInfo }
            val target = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        }
        error("$what refused $PRESS_TRIES presses")
    }

    /** What [read] takes from the Viewer's controls, read again when they hide while it reads. */
    private fun <T> fresh(read: () -> T): T {
        repeat(PRESS_TRIES - 1) {
            try {
                return read()
            } catch (_: StaleObjectException) {
                // The controls hid as they were read; looking them up again brings them back.
            }
        }
        return read()
    }

    /** Leaves the Viewer for the series page. */
    fun leaveViewer() {
        device.pressBack()
        if (device.wait(Until.hasObject(By.text(EPISODE_COUNT)), SeriesLiveDriver.SHORT_WAIT_MS) != true) device.pressBack()
    }

    /** Leaves the Viewer while [episode] plays; returns where it was, which the series page's Continue then names. */
    fun leavePlaying(episode: String): Long {
        val at = awaitPlayback("\"$episode\" playing") { it.state == PLAYING }.positionMs
        leaveViewer()
        live.waitFor("Continue naming \"$episode\"", By.text("Continue · $episode"))
        return at
    }

    /** Checks [episode], just reopened, plays from within [RESUME_NEAR_MS] of [leftAt]. */
    fun assertResumesAt(step: String, episode: String, leftAt: Long, how: String) {
        val resumed = awaitPlayback("\"$episode\" to play again") { it.state == PLAYING }.positionMs
        live.report(step, "\"$episode\" left at ${leftAt.tenths()} s resumed at ${resumed.tenths()} s $how")
        assertTrue("It resumes where it was left", resumed - leftAt in -RESUME_NEAR_MS..RESUME_NEAR_MS)
    }

    /** Opens the stream list; returns the stream playing and another one to switch to. */
    fun switchStream(): Pair<String, String> {
        val current = streamOnScreen()
        val before = labels()
        press("the stream pill", By.desc(STREAM))
        val listed = live.await("the stream list", { labels() - before.toSet() - current }) { it.isNotEmpty() }
        live.report("streams", "playing \"$current\"; listed $listed")
        return current to (listed.firstOrNull { it != current } ?: error("The episode offers one stream: $listed"))
    }

    /**
     * The row of the entry watched after [entry]: the one above it in the series page's list, which runs newest first.
     * A row reads as the entry's name, then any detail line ([rowNames]). Scrolls the list to find it, then back to the top.
     */
    fun entryAfter(entry: String): String {
        val list = live.waitFor("the series page's list", By.scrollable(true))
        fun rows() = list.children.mapNotNull { row -> runCatching { row.text ?: row.children.firstNotNullOfOrNull { it.text } }.getOrNull() }
        var above: String? = null
        for (attempt in 0 until ENTRY_SCROLLS) {
            val shown = rows()
            val at = shown.indexOfFirst { rowNames(it, entry) }
            if (at > 0) {
                above = shown[at - 1]
                break
            }
            // Not shown yet, or at the top edge with the row above it hidden.
            list.scroll(if (at == 0) Direction.UP else Direction.DOWN, if (at == 0) 0.3f else 0.8f)
        }
        // A single scrollUntil gives up after a few screens of a long list.
        for (attempt in 0 until ENTRY_SCROLLS) {
            if (device.hasObject(By.text(WATCH_BUTTON))) break
            list.fling(Direction.UP)
        }
        return checkNotNull(above) { "\"$entry\" is not listed with a row above it" }
    }

    /** Whether a series page [row] is the entry named [name]: a row whose texts merge reads as its name, then its detail. */
    fun rowNames(row: String, name: String) = row == name || row.startsWith("$name, ")

    /** Every text the target shows. */
    fun labels(): List<String> = device.findObjects(By.pkg(live.targetPackage))
        .mapNotNull { runCatching { it.text }.getOrNull() }
        .filter(String::isNotBlank)

    /** The episode's length, the longest time the Viewer's timeline shows. */
    fun durationOnScreen(): Long {
        repeat(PRESS_TRIES) {
            live.viewerControl("the timeline", TIMELINE)
            // The controls may hide between finding the timeline and reading its times; then they are shown again.
            labels().mapNotNull(::timeMs).maxOrNull()?.let { return it }
        }
        error("The timeline shows no times; on screen: ${live.screenText()}")
    }

    /** Seeks to [positionMs] through the timeline, which seeks to where a drag along it is let go. */
    fun seekTo(positionMs: Long) {
        val durationMs = durationOnScreen()
        val track = fresh { live.viewerControl("the timeline", TIMELINE).visibleBounds }
        val x = track.left + (track.width() * positionMs.toDouble() / durationMs).toInt().coerceIn(1, track.width() - 1)
        device.drag(x - 1, track.centerY(), x, track.centerY(), SEEK_DRAG_STEPS)
    }

    /** The playback position once it satisfies [done]. */
    fun awaitPosition(what: String, done: (Long) -> Boolean): Long = awaitPlayback(what) { done(it.positionMs) }.positionMs

    fun awaitPlayback(what: String, done: (Playback) -> Boolean): Playback {
        val deadline = System.currentTimeMillis() + PLAYBACK_TIMEOUT_MS
        var last: Playback? = null
        while (System.currentTimeMillis() < deadline) {
            last = playback() ?: last
            if (last != null && done(last)) return last
            Thread.sleep(SeriesLiveDriver.POLL_MS)
        }
        error("Timed out waiting for $what; last playback: $last; on screen: ${live.screenText()}")
    }

    /**
     * What the target's player is doing, as its media session last told the system: the position it reported, carried
     * forward by the time since while it plays. Null before the Viewer opens a session.
     */
    fun playback(): Playback? {
        val sessions = device.executeShellCommand("dumpsys media_session").substringAfter("package=${live.targetPackage}\n", "")
        val (state, position, speed, updated) = SESSION_STATE.find(sessions)?.destructured ?: return null
        val moved = if (state == PLAYING) (SystemClock.elapsedRealtime() - updated.toLong()) * speed.toFloat() else 0f
        return Playback(state, position.toLong() + moved.toLong())
    }

    /** When, in wall-clock milliseconds, the app logged starting each episode lookup since [sinceMs]. */
    fun lookupsSince(sinceMs: Long): List<Long> = device.executeShellCommand("logcat -d -v epoch -s SeriesReader:I")
        .lines()
        .filter { it.endsWith("Looking up an episode") }
        .mapNotNull { it.trim().substringBefore(' ').toDoubleOrNull()?.let { seconds -> (seconds * 1000).toLong() } }
        .filter { it >= sinceMs }

    data class Playback(val state: String, val positionMs: Long)

    companion object {
        const val EXTENSION_NAME = "extensionName"
        const val SERIES_QUERY = "seriesQuery"
        const val STREAM = "Stream"
        const val PLAYING = "PLAYING"
        const val EDIT_TEXT = "android.widget.EditText"
        private val NOT_EDIT_TEXT: Pattern = Pattern.compile("^(?!${Pattern.quote(EDIT_TEXT)}$).*")
        val EPISODE_COUNT: Pattern = Pattern.compile("(.+ · )?\\d+ episodes?")
        val WATCH_BUTTON: Pattern = Pattern.compile("Start|Continue · .*")

        /** An episode's lookup can take about 35 s for some extensions, and its stream must then buffer. */
        const val PLAYBACK_TIMEOUT_MS = 90_000L
        const val PRESS_TRIES = 3

        /** A session's line in `dumpsys media_session`: "state=PlaybackState {state=PLAYING(3), position=…, …, speed=1.0, updated=…". */
        val SESSION_STATE = Regex("""state=PlaybackState \{state=(\w+)\(\d+\), position=(-?\d+), .*?speed=(-?[\d.]+), updated=(\d+)""")

        /** How far a reopened episode may resume from where it was left: progress is saved as it is left. */
        const val RESUME_NEAR_MS = 2_500L
        private const val SEEK_DRAG_STEPS = 5

        /** Enough screens of a long series' list to reach an entry near its oldest end. */
        private const val ENTRY_SCROLLS = 120
        private val TIMELINE = By.clazz("android.widget.ProgressBar")

        /** A timeline time, "m:ss" or "h:mm:ss", in milliseconds; null for any other text. */
        private val TIME = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})""")

        private fun timeMs(text: String): Long? = TIME.matchEntire(text)?.destructured?.let { (h, m, s) ->
            ((h.toLongOrNull() ?: 0) * 3600 + m.toLong() * 60 + s.toLong()) * 1000
        }

        fun Long.seconds() = this / 1000
        fun Long.tenths() = this / 100 / 10.0
    }
}
