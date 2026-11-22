package com.theoriacodex.app.live

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.theoriacodex.app.di.SeriesDependencies
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.series.SERIES_UPDATE_JOB_ID
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.continueEntry
import com.theoriacodex.data.repository.inReadingOrder
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesSource
import java.util.regex.Pattern
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves reading a series from a Mihon extension end to end in the real debug app. It talks to the live site, so it
 * runs only with `-e extensionSourceLive true`, after an extension file has been pushed to the app's
 * `mihon-extensions` folder (see `mihon-host/README.md`).
 *
 * The tests are one story across app restarts, so run each as its own `am instrument` call, which starts the app's
 * process anew: [aSeriesIsBrowsedReadResumedAndFollowed], [aFollowedSeriesIsOnTheShelfAfterARestart], then the
 * new-chapter story: [followingASeriesAsksToNotifyAndSchedulesTheCheck], [aFollowedSeriesForgetsItsNewestChapter], the background check run by hand from a shell
 * (`cmd jobscheduler run`, see `mihon-host/README.md`), [aNewChapterNotificationOpensItsSeries] and
 * [unfollowingTheLastSeriesCancelsTheCheck].
 *
 * The report is counts and labels only, never images: provider media stays off screenshots and logs.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionSourceLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val series: SeriesDependencies by lazy {
        val container = runBlocking { (context as TheoriaAppContainerOwner).awaitAppContainer() }
        checkNotNull(container.series) { "This build has no extension host" }
    }

    @Before
    fun requireExplicitOptIn() {
        val enabled = InstrumentationRegistry.getArguments().getString(OPT_IN)?.toBooleanStrictOrNull() ?: false
        assumeTrue("The extension source test requires $OPT_IN=true", enabled)
    }

    @Test
    fun aSeriesIsBrowsedReadResumedAndFollowed() {
        val source = runBlocking { series.sources() }.firstOrNull() ?: error("No extension loaded")
        // What the tab should list: the first source's popular series, asked for directly.
        val listed = runBlocking { source.popular(1).series.take(CANDIDATES).first { readable(source, it) } }
        report("source", "${source.name} [${source.language}] lists \"${listed.title}\"")

        openSeriesTab()
        searchEverySource(source, listed)
        tap("the listed series", scrolledTo(By.text(listed.title)))
        val chapters = waitFor("the chapter count", By.text(CHAPTER_COUNT), RESULTS_TIMEOUT_MS).text.substringBefore(' ').toInt()
        report("series", "chapters=$chapters")
        assertTrue("A series lists more than one chapter", chapters > 1)

        val (chapter, next) = continueChapters(listed)
        tap("the button that starts reading", By.text(Pattern.compile("Start|Continue · .*")))
        viewerControl("the chapter's name as the Viewer's title", By.textStartsWith("${listed.title} · ${chapter.entry.name}"))
        val pages = pageTile(1.coerceAtLeast(chapter.lastPage)).contentDescription.substringAfterLast(' ').toInt()
        // The controls are showing here; a chapter is not a post to like or save to a Codex.
        assertNull("A chapter offers no Like", device.findObject(By.desc("Like post")))
        assertNull("A chapter offers no Save", device.findObject(By.desc("Save to Codex")))
        report("viewer", "title names \"${chapter.entry.name}\" pages=$pages")
        assertTrue("A chapter has pages", pages > 0)

        val target = minOf(TARGET_PAGE, pages)
        pageTile(target).click()
        awaitProgress(listed, chapter.entry.address) { it.lastPage == target }
        device.pressBack()

        tap("Continue naming the chapter left unfinished", By.text("Continue · ${chapter.entry.name}"))
        // The top bar counts the page on screen; the chapter opens on its first page until its pages arrive.
        viewerControl("page $target as the page Continue resumes on", By.textEndsWith("$target / $pages"))
        report("resume", "Continue reopened \"${chapter.entry.name}\" on page $target of $pages")

        val mode = chooseOtherReadingMode()
        val stored = runBlocking { series.repository.series(listed.id) }?.readingMode
        report("mode", "chose \"$mode\", the series stores $stored")
        assertTrue("The chosen mode is stored on the series", stored?.name.equals(mode.replace(' ', '_'), ignoreCase = true))

        goToLastPage(pages)
        awaitProgress(listed, chapter.entry.address) { it.read }
        swipeToNextPost(mode)
        viewerControl("the next chapter's name as the title", By.textStartsWith("${listed.title} · ${next.entry.name}"))
        assertTrue("The next chapter reads in the chosen mode", readingModeChecked(mode))
        report("next", "past the last page opened \"${next.entry.name}\", still \"$mode\"")
        device.pressBack()

        waitFor("the finished chapter marked Read on the series page", By.text("Read"))
        tap("Continue naming the next chapter", By.text("Continue · ${next.entry.name}"))
        viewerControl("the next chapter's name as the title", By.textStartsWith("${listed.title} · ${next.entry.name}"))
        assertTrue("The series reopens in the chosen mode", readingModeChecked(mode))
        report("reopen", "\"${chapter.entry.name}\" shows Read; the series reopened in \"$mode\"")
        device.pressBack()

        follow(listed)
    }

    /** The new-chapter story's start when the reading story above was not run: follows the first listed series. */
    @Test
    fun followingASeriesAsksToNotifyAndSchedulesTheCheck() {
        val listed = runBlocking { series.sources().first().popular(1) }.series.first()
        openSeriesTab()
        tap("the listed series", By.text(listed.title), RESULTS_TIMEOUT_MS)
        waitFor("the chapter count", By.text(CHAPTER_COUNT), RESULTS_TIMEOUT_MS)
        follow(listed)
        report("job", "following scheduled the check every ${awaitCheckJob(scheduled = true)?.intervalMillis?.milliseconds}")
    }

    @Test
    fun aFollowedSeriesIsOnTheShelfAfterARestart() {
        val followed = runBlocking { series.repository.observeFollowed().first() }
        assertTrue("A series was followed before the restart", followed.isNotEmpty())

        openSeriesTab()
        waitFor("the Following shelf", By.text("Following"))
        val shelved = followed.first()
        waitFor("the followed series on the shelf", By.text(shelved.series.title))
        report("shelf", "\"${shelved.series.title}\" is on the shelf, unread=${shelved.unreadCount}")
    }

    /**
     * Makes the followed series' newest chapter one the app has not seen, so the next check finds it as new: stores
     * the series' chapter list without it, as if the source had not listed it yet.
     */
    @Test
    fun aFollowedSeriesForgetsItsNewestChapter() {
        val followed = runBlocking { series.repository.observeFollowed().first() }.first().series
        val entries = runBlocking { series.repository.entries(followed.id) }
        runBlocking { series.repository.store(followed, entries.drop(1).map { it.entry }) }
        val left = runBlocking { series.repository.entries(followed.id) }
        assertTrue("The newest chapter is no longer stored", left.none { it.entry.address == entries.first().entry.address })
        report("forgotten", "\"${followed.title}\" no longer stores \"${entries.first().entry.name}\"; chapters ${entries.size} -> ${left.size}")
    }

    /** Run after the background check posted a notification: tapping it opens that series' page. */
    @Test
    fun aNewChapterNotificationOpensItsSeries() {
        val followed = runBlocking { series.repository.observeFollowed().first() }.first()
        report("shelf", "\"${followed.series.title}\" unread=${followed.unreadCount}")
        device.openNotification()
        val notification = waitFor("the series' notification", By.text(followed.series.title))
        report("notification", "\"${notification.text}\" is in the shade")
        notification.click()
        waitFor("the series' page", By.text(CHAPTER_COUNT), RESULTS_TIMEOUT_MS)
        waitFor("the series' page naming the series", By.text(followed.series.title))
        waitFor("the series' page showing it followed", By.text("Following"))
        report("opened", "the notification opened \"${followed.series.title}\"'s page")
    }

    @Test
    fun unfollowingTheLastSeriesCancelsTheCheck() {
        val followed = runBlocking { series.repository.observeFollowed().first() }
        assertTrue("Exactly one series is followed", followed.size == 1)
        report("job", "before: scheduled every ${awaitCheckJob(scheduled = true)?.intervalMillis?.milliseconds}")
        openSeriesTab()
        tap("the followed series on the shelf", By.text(followed.single().series.title))
        tap("Following, to unfollow", By.text("Following"))
        waitFor("the series to read as not followed", By.text("Follow"))
        awaitCheckJob(scheduled = false)
        report("job", "after unfollowing the last series: cancelled")
    }

    /** Follows the series whose page is open, answering the notification question the first follow asks. */
    private fun follow(listed: Series) {
        if (device.wait(Until.hasObject(By.text("Following")), SHORT_WAIT_MS) != true) {
            tap("Follow", By.text("Follow"))
            // An install that already answered is not asked again.
            device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_button")), SHORT_WAIT_MS)?.let { allow ->
                report("permission", "the first follow asked to send notifications")
                allow.click()
            }
        }
        waitFor("the series to read as followed", By.text("Following"))
        report("follow", "\"${listed.title}\" is followed")
    }

    /** Waits until the background check is [scheduled] or not, and returns it. */
    private fun awaitCheckJob(scheduled: Boolean): JobInfo? {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (true) {
            val job = scheduler.getPendingJob(SERIES_UPDATE_JOB_ID)
            if ((job != null) == scheduled) return job
            check(System.currentTimeMillis() < deadline) { "The check stayed ${if (scheduled) "unscheduled" else "scheduled"}" }
            Thread.sleep(POLL_MS)
        }
    }

    /** Launches the app afresh on the Series tab, which also proves the tab is in the bar. */
    private fun openSeriesTab() {
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        tap("the Series tab", By.desc(TopLevelDestination.Series.label), LAUNCH_TIMEOUT_MS)
        waitFor("the Series tab's source list", By.text("Browse"), RESULTS_TIMEOUT_MS)
    }

    /**
     * Whether [listed] still has a chapter to continue and one after it, so the test can run again on an emulator that
     * kept an earlier run's progress. A series never opened here is asked for its chapters directly.
     */
    private suspend fun readable(source: SeriesSource, listed: Series): Boolean {
        val stored = series.repository.series(listed.id) ?: return source.entries(source.details(listed)).size > 1
        val entries = series.repository.entries(listed.id).inReadingOrder()
        val resume = continueEntry(stored, entries) ?: return false
        return !resume.read && entries.last() != resume
    }

    /** The chapter the series page's button opens and the one after it, read from what the page stored. */
    private fun continueChapters(listed: Series): Pair<StoredSeriesEntry, StoredSeriesEntry> = runBlocking {
        val stored = checkNotNull(series.repository.series(listed.id)) { "Opening the series did not store it" }
        val entries = series.repository.entries(listed.id).inReadingOrder()
        val resume = checkNotNull(continueEntry(stored, entries)) { "The series has no chapter to continue" }
        resume to entries[entries.indexOf(resume) + 1]
    }

    /** Searches every source for the first word of [listed]'s title and checks each visible source answers in a group of its own. */
    private fun searchEverySource(source: SeriesSource, listed: Series) {
        val sources = runBlocking { series.sources() }
        val field = waitFor("the search field", By.clazz("android.widget.EditText"))
        field.text = listed.title.substringBefore(' ')
        field.click()
        device.pressEnter()
        waitFor("a result count beside a source's name", By.text(Pattern.compile("\\d+")), RESULTS_TIMEOUT_MS)
        val groups = device.findObjects(By.text(Pattern.compile(sources.map { Pattern.quote(it.name) }.distinct().joinToString("|")))).size
        report("search", "sources=${sources.size} groups on screen=$groups, first is ${source.name}")
        assertTrue("Each source answers in its own group", groups > 1 || sources.size == 1)
        tap("Clear", By.desc("Clear"))
        waitFor("the browse list back", By.text("Browse"))
    }

    /** [selector], after scrolling the list down until something matches it. */
    private fun scrolledTo(selector: BySelector): BySelector {
        repeat(SCROLLS) {
            if (device.wait(Until.hasObject(selector), SHORT_WAIT_MS) == true) return selector
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, SWIPE_STEPS)
        }
        return selector
    }

    /** Waits until the series' record of the chapter at [address] satisfies [reached]. */
    private fun awaitProgress(listed: Series, address: String, reached: (StoredSeriesEntry) -> Boolean) {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (true) {
            val entry = runBlocking { series.repository.entries(listed.id) }.first { it.entry.address == address }
            if (reached(entry)) {
                report("progress", "stored page ${entry.lastPage} of ${entry.pageCount}, read=${entry.read}")
                return
            }
            check(System.currentTimeMillis() < deadline) { "Progress stayed at page ${entry.lastPage} of ${entry.pageCount}, read=${entry.read}" }
            Thread.sleep(POLL_MS)
        }
    }

    /** Opens the page overview, picks whichever sideways mode is not the current one, and returns its label. */
    private fun chooseOtherReadingMode(): String {
        viewerControl("the overview button", By.desc("Open media overview")).click()
        val mode = SIDEWAYS_MODES.first { it != checkedReadingMode() }
        tap("the mode \"$mode\"", By.text(mode))
        device.pressBack()
        return mode
    }

    /** Whether the page overview shows [mode] as the one in use. */
    private fun readingModeChecked(mode: String): Boolean {
        viewerControl("the overview button", By.desc("Open media overview")).click()
        val checked = checkedReadingMode()
        device.pressBack()
        return checked == mode
    }

    /** The label of the checked button in the open overview's mode switch; the label is the button's own text or its child's. */
    private fun checkedReadingMode(): String? {
        waitFor("the reading mode switch", By.text(SIDEWAYS_MODES[0]))
        return device.findObjects(By.checked(true))
            .flatMap { button -> runCatching { listOfNotNull(button.text) + button.children.mapNotNull { it.text } }.getOrDefault(emptyList()) }
            .firstOrNull { it in READING_MODES }
    }

    /** Hops along the page strip, a few tiles at a time, until the top bar counts the last page. */
    private fun goToLastPage(pages: Int) {
        repeat(pages * 2) {
            if (device.hasObject(By.textEndsWith("$pages / $pages"))) return
            viewerControl("the page strip", PAGE_TILE)
            // The strip scrolls as pages are picked; a tile that has gone is skipped and the next round finds it again.
            val farthest = device.findObjects(PAGE_TILE)
                .mapNotNull { tile -> runCatching { tile.contentDescription }.getOrNull() }
                .maxByOrNull { it.removePrefix("Go to page ").substringBefore(' ').toInt() }
            runCatching { farthest?.let { device.findObject(By.desc(it))?.click() } }
            Thread.sleep(POLL_MS)
        }
        viewerControl("the last page", By.textEndsWith("$pages / $pages"))
    }

    /** Swipes past the last page: a finger moving left reads on left to right, and moving right reads on right to left. */
    private fun swipeToNextPost(mode: String) {
        val y = device.displayHeight / 2
        val (from, to) = (device.displayWidth * 4 / 5 to device.displayWidth / 5).let { if (mode == SIDEWAYS_MODES[0]) it else it.second to it.first }
        device.swipe(from, y, to, y, SWIPE_STEPS)
    }

    /** The page strip's tile for [page] of the open chapter. */
    private fun pageTile(page: Int): UiObject2 = viewerControl("the tile for page $page", By.descStartsWith("Go to page $page of "))

    /**
     * One of the Viewer's controls. The Viewer hides them after a few seconds, often before a chapter's pages have
     * arrived, so each wait that finds none taps the page to bring them back.
     */
    private fun viewerControl(what: String, selector: BySelector): UiObject2 {
        val deadline = System.currentTimeMillis() + RESULTS_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            device.wait(Until.findObject(selector), CONTROLS_WAIT_MS)?.let { return it }
            device.click(device.displayWidth / 2, device.displayHeight / 2)
        }
        error("The Viewer did not show $what; on screen: ${screenText()}")
    }

    private fun waitFor(what: String, selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs) ?: error("Timed out waiting for $what; on screen: ${screenText()}")

    private fun tap(what: String, selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS) = waitFor(what, selector, timeoutMs).click()

    /** The labels on screen, for a failure message; text only, so no provider media leaves the device. */
    private fun screenText(): String = device.findObjects(By.pkg(context.packageName))
        // The screen keeps changing while it is read; an element that has gone is skipped.
        .flatMap { element -> runCatching { listOfNotNull(element.text, element.contentDescription) }.getOrDefault(emptyList()) }
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" | ")
        .take(SCREEN_TEXT_CHARS)

    private fun report(step: String, text: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("series", "$step: $text") })
    }

    private companion object {
        const val OPT_IN = "extensionSourceLive"
        val CHAPTER_COUNT: Pattern = Pattern.compile("\\d+ chapters?")

        /** The page the test stops on, far enough in that resuming there cannot be the start by accident. */
        const val TARGET_PAGE = 3

        /** How many popular series are tried for one with chapters left to read. */
        const val CANDIDATES = 10
        val SIDEWAYS_MODES = listOf("Left to right", "Right to left")
        val READING_MODES = SIDEWAYS_MODES + "Vertical"
        val PAGE_TILE: BySelector = By.descStartsWith("Go to page ")
        const val SCROLLS = 6
        const val SWIPE_STEPS = 20

        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val RESULTS_TIMEOUT_MS = 45_000L
        const val UI_TIMEOUT_MS = 30_000L
        const val SHORT_WAIT_MS = 2_000L
        const val POLL_MS = 200L
        const val CONTROLS_WAIT_MS = 2_500L
        const val SCREEN_TEXT_CHARS = 600
    }
}
