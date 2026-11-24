package com.theoriacodex.app.live

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.database.sqlite.SQLiteDatabase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.theoriacodex.app.series.SERIES_UPDATE_JOB_ID
import com.theoriacodex.app.series.notifySeriesUpdate
import com.theoriacodex.data.android.room.TheoriaRoomDatabase
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.continueEntry
import com.theoriacodex.data.repository.inReadingOrder
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.SeriesStatus
import java.util.regex.Pattern
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves reading a series from a Mihon extension end to end in the real debug app. It talks to the live site, so it
 * runs only with `-e extensionSourceLive true`, after an extension has been turned on, which `ExtensionsLiveTest` does
 * (see `extension-host/README.md`).
 *
 * The tests are one story across app restarts, so run each as its own `am instrument` call, which starts the app's
 * process anew: [aSeriesIsBrowsedReadResumedAndFollowed], [aFollowedSeriesIsOnTheShelfAfterARestart], then the
 * new-chapter story: [followingASeriesAsksToNotifyAndSchedulesTheCheck], [aFollowedSeriesForgetsItsNewestChapter], the background check run by hand from a shell
 * (`cmd jobscheduler run`, see `extension-host/README.md`), [aNewChapterNotificationOpensItsSeries], [aNotificationOpensItsSeriesOverAnotherSeriesPage] and
 * [unfollowingTheLastSeriesCancelsTheCheck].
 *
 * The report is counts and labels only, never images: provider media stays off screenshots and logs.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionSourceLiveTest {
    private val live = SeriesLiveDriver()
    private val device = live.device
    private val context = live.context
    private val series get() = live.series

    @Before
    fun requireExplicitOptIn() = live.requireOptIn()

    @Test
    fun aSeriesIsBrowsedReadResumedAndFollowed() {
        val source = runBlocking { series.sources() }.firstOrNull() ?: error("No extension is on; run ExtensionsLiveTest first")
        // What the tab should list: the first source's popular series, asked for directly.
        val listed = unreadSeries(source)
        live.report("source", "${source.name} [${source.language}] lists \"${listed.title}\"")

        live.openSeriesTab()
        searchEverySource(source, listed)
        live.tap("the listed series", scrolledTo(By.text(listed.title)))
        val counted = live.waitFor("the chapter count", By.text(CHAPTER_COUNT), SeriesLiveDriver.RESULTS_TIMEOUT_MS).text
        val chapters = counted.substringAfterLast(" · ").substringBefore(' ').toInt()
        live.report("series", "chapters=$chapters")
        assertTrue("A series lists more than one chapter", chapters > 1)
        readAbout(listed)

        val (chapter, next) = continueChapters(listed)
        live.tap("the button that starts reading", By.text(Pattern.compile("Start reading|Continue reading .+")))
        viewerControl("the chapter's name as the Viewer's title", By.textStartsWith("${listed.title} · ${chapter.entry.name}"))
        val pages = pageTile(chapter.position.toInt().coerceAtLeast(1)).contentDescription.substringAfterLast(' ').toInt()
        // The controls are showing here; a chapter is not a post to like or save to a Codex.
        assertNull("A chapter offers no Like", device.findObject(By.desc("Like post")))
        assertNull("A chapter offers no Save", device.findObject(By.desc("Save to Codex")))
        live.report("viewer", "title names \"${chapter.entry.name}\" pages=$pages")
        assertTrue("A chapter has pages", pages > 0)

        openInfoFromTheDock()

        val target = minOf(TARGET_PAGE, pages)
        pageTile(target).click()
        awaitProgress(listed, chapter.entry.address) { it.position == target.toLong() }
        device.pressBack()

        live.tap("Continue naming the chapter left unfinished", By.text("Continue reading ${chapter.entry.name}"))
        // The top bar counts the page on screen; the chapter opens on its first page until its pages arrive.
        viewerControl("page $target as the page Continue resumes on", By.textEndsWith("$target / $pages"))
        live.report("resume", "Continue reopened \"${chapter.entry.name}\" on page $target of $pages")

        val mode = chooseOtherReadingMode()
        val stored = runBlocking { series.repository.series(listed.id) }?.readingMode
        live.report("mode", "chose \"$mode\", the series stores $stored")
        assertTrue("The chosen mode is stored on the series", stored?.name.equals(mode.replace(' ', '_'), ignoreCase = true))

        goToLastPage(pages)
        awaitProgress(listed, chapter.entry.address) { it.read }
        swipeToNextPost(mode)
        viewerControl("the next chapter's name as the title", By.textStartsWith("${listed.title} · ${next.entry.name}"))
        assertTrue("The next chapter reads in the chosen mode", readingModeChecked(mode))
        live.report("next", "past the last page opened \"${next.entry.name}\", still \"$mode\"")

        // The chapter just finished was left on its last page; picked again, it starts over.
        // The strip redraws as chapters load, so an element found can be gone by the time it is tapped.
        val picked = By.desc("Open ${listed.title} · ${chapter.entry.name}")
        generateSequence { runCatching { viewerControl("the finished chapter in the strip", picked).click() } }.take(STRIP_TAPS).first { it.isSuccess }
        viewerControl("the finished chapter's name as the title", By.textStartsWith("${listed.title} · ${chapter.entry.name}"))
        viewerControl("the finished chapter on its first page", By.descStartsWith("Page 1 of "))
        live.report("reread", "the finished \"${chapter.entry.name}\", picked from the strip, opened on page 1")
        device.pressBack()

        live.waitFor("the finished chapter marked Read on the series page", By.text("Read"))
        live.tap("Continue naming the next chapter", By.text("Continue reading ${next.entry.name}"))
        viewerControl("the next chapter's name as the title", By.textStartsWith("${listed.title} · ${next.entry.name}"))
        assertTrue("The series reopens in the chosen mode", readingModeChecked(mode))
        live.report("reopen", "\"${chapter.entry.name}\" shows Read; the series reopened in \"$mode\"")
        device.pressBack()

        follow(listed)
    }

    /** Read as one column, a chapter's end card clears the dock, and a push up past it opens the next chapter. */
    @Test
    fun aVerticalChapterReadsOnPastItsLastPage() {
        val source = runBlocking { series.sources() }.firstOrNull() ?: error("No extension is on; run ExtensionsLiveTest first")
        val listed = unreadSeries(source)
        live.openSeriesTab()
        live.tap("the listed series", scrolledTo(By.text(listed.title)))
        live.waitFor("the chapter count", By.text(CHAPTER_COUNT), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        val (chapter, next) = continueChapters(listed)
        live.tap("the button that starts reading", By.text(Pattern.compile("Start reading|Continue reading .+")))
        viewerControl("the chapter's name as the Viewer's title", By.textStartsWith("${listed.title} · ${chapter.entry.name}"))
        val pages = pageTile(chapter.position.toInt().coerceAtLeast(1)).contentDescription.substringAfterLast(' ').toInt()
        viewerControl("the overview button", By.desc("Open media overview")).click()
        live.tap("the mode \"$VERTICAL\"", By.text(VERTICAL))
        device.pressBack()
        openInfoFromTheDock()
        goToLastPage(pages)

        val x = device.displayWidth / 2
        val pushUp = { device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 4, SWIPE_STEPS) }
        val onChapter = { runBlocking { series.repository.series(listed.id) }?.lastOpenedEntry == chapter.entry.address }
        val cardEdge = { device.findObject(By.text("Next"))?.let { runCatching { it.visibleBounds.bottom }.getOrNull() } }
        // The jump to the last page already ends at the end card when the last pages are short, and a push there
        // opens the next chapter. So the card is looked for before every push, with the controls hidden: an element
        // they cover is left out of what UiAutomator can see. A page can also be several screens tall, so the card
        // can be many pushes away.
        val cardShown = {
            repeat(CONTROL_TAPS) {
                if (device.hasObject(By.desc("Info"))) {
                    device.click(x, device.displayHeight / 4)
                    device.wait(Until.gone(By.desc("Info")), SeriesLiveDriver.CONTROLS_WAIT_MS)
                }
            }
            device.wait(Until.hasObject(By.text("Next")), SeriesLiveDriver.SHORT_WAIT_MS) == true
        }
        var pushes = 0
        while (!cardShown() && pushes < END_PUSHES) {
            pushUp()
            pushes++
        }
        // A push stops wherever its fling ends. Short slow drags scroll the rest of the way and, at the end, are too
        // short and too slow to open the next chapter; the card is at the end once one of them leaves it in place.
        val nudge = (NUDGE_DP * context.resources.displayMetrics.density).toInt()
        var settled = cardEdge()
        for (attempt in 0 until NUDGES) {
            device.swipe(x, device.displayHeight / 2, x, device.displayHeight / 2 - nudge, NUDGE_STEPS)
            Thread.sleep(SeriesLiveDriver.POLL_MS * 3)
            val now = cardEdge()
            if (now == settled) break
            settled = now
        }
        assertTrue("The Viewer left \"${chapter.entry.name}\" before its end card was reached; on screen: ${live.screenText()}", onChapter())
        val cardBottom = checkNotNull(settled) { "The end card did not show after $pushes pushes; on screen: ${live.screenText()}" }
        // Scrolling down hid the controls; a tap on the page above the card shows them over it.
        device.click(x, device.displayHeight / 4)
        val dockTop = live.waitFor("the dock", By.desc("Info")).visibleBounds.top
        live.report("vertical", "after $pushes pushes the end card ends at y=$cardBottom, the dock starts at y=$dockTop")
        assertTrue("The dock does not cover the end card", cardBottom <= dockTop)

        pushUp()
        viewerControl("the next chapter's name as the title", By.textStartsWith("${listed.title} · ${next.entry.name}"))
        live.report("vertical", "a push up past the last page of ${pages} opened \"${next.entry.name}\"")
    }

    /** A followed series whose extension is off keeps its page, says why it cannot be read, and reads again once it is on. */
    @Test
    fun aSeriesWhoseExtensionIsOffNamesThatAndCannotBeRead() {
        val followed = runBlocking { series.repository.observeFollowed().first() }.firstOrNull() ?: error("Follow a series first")
        val extension = runBlocking { series.extensions.installed() }.first { it.on }
        val signer = checkNotNull(extension.signer)
        runBlocking { series.extensions.setOn(extension.id, signer, false) }
        try {
            live.openSeriesTab(view = SeriesLiveDriver.FOLLOWING)
            live.tap("the followed series in Following", By.text(followed.series.title))
            live.waitFor("the page saying its extension cannot be used", By.text("Extension unavailable"))
            live.waitFor("the button to the Extensions page", By.text("Extensions"))
            live.waitFor("the stored chapter list", By.text(CHAPTER_COUNT))
            live.waitFor("Unfollow still on offer", By.text("Following"))
            val start = live.waitFor("the button that starts reading", By.text(Pattern.compile("Start reading|Continue reading .+")))
            // The label sits inside the button, which is what is disabled.
            val enabled = generateSequence(start) { it.parent }.take(3).all { it.isEnabled }
            val titled = device.findObjects(By.text(followed.series.title)).size
            live.report("off", "page says \"Extension unavailable\", title shown $titled times, Continue enabled=$enabled")
            assertTrue("Continue is disabled while the extension is off", !enabled)
        } finally {
            runBlocking { series.extensions.setOn(extension.id, signer, true) }
        }
        live.waitFor("Continue working again once the extension is on", By.text(Pattern.compile("Start reading|Continue reading .+")).enabled(true))
        live.report("off", "turning the extension on again made the page readable")
    }

    /**
     * Run after the notification permission was taken back (`pm revoke`, then `pm clear-permission-flags` for
     * `user-set user-fixed`) with a series already followed: following one more asks again. The series is unfollowed
     * after, so the shelf is as it was.
     */
    @Test
    fun aLaterFollowAsksToNotifyWhileThePermissionIsMissing() {
        val followed = runBlocking { series.repository.observeFollowed().first() }.map { it.series.id }
        assertTrue("A series is already followed", followed.isNotEmpty())
        val listed = runBlocking { series.sources().first().popular(1) }.series.first { it.id !in followed }
        live.openSeriesTab()
        live.tap("a series not followed yet", scrolledTo(By.text(listed.title)))
        live.waitFor("the chapter count", By.text(CHAPTER_COUNT), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        try {
            live.tap("Follow", By.text("Follow"))
            val allow = By.res("com.android.permissioncontroller", "permission_allow_button")
            live.waitFor("the system's question", allow)
            live.report("permission", "following one more series (${followed.size} followed before) asked to send notifications")
            // The dialog ignores taps while it slides in.
            repeat(SCROLLS) { device.wait(Until.findObject(allow), SeriesLiveDriver.SHORT_WAIT_MS)?.click() }
            live.waitFor("the series to read as followed", By.text("Following"))
        } finally {
            runBlocking { series.repository.setFollowed(listed.id, false) }
        }
    }

    /** The new-chapter story's start when the reading story above was not run: follows the first listed series. */
    @Test
    fun followingASeriesAsksToNotifyAndSchedulesTheCheck() {
        val listed = runBlocking { series.sources().first().popular(1) }.series.first()
        live.openSeriesTab()
        live.tap("the listed series", By.text(listed.title), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        live.waitFor("the chapter count", By.text(CHAPTER_COUNT), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        follow(listed)
        live.report("job", "following scheduled the check every ${awaitCheckJob(scheduled = true)?.intervalMillis?.milliseconds}")
    }

    @Test
    fun aFollowedSeriesIsOnTheShelfAfterARestart() {
        val followed = runBlocking { series.repository.observeFollowed().first() }
        assertTrue("A series was followed before the restart", followed.isNotEmpty())

        live.openSeriesTab(view = SeriesLiveDriver.FOLLOWING)
        val shelved = followed.first()
        live.waitFor("the followed series in Following", By.text(shelved.series.title))
        live.report("shelf", "\"${shelved.series.title}\" is in Following, unread=${shelved.unreadCount}")
    }

    /**
     * Makes the followed series' newest chapter one the app has not seen, so the next check finds it as new. The app
     * itself never forgets a chapter (one its source drops is kept, hidden, and is not new when it returns), so the
     * row is deleted from the database directly.
     */
    @Test
    fun aFollowedSeriesForgetsItsNewestChapter() {
        val followed = runBlocking { series.repository.observeFollowed().first() }.first().series
        val entries = runBlocking { series.repository.entries(followed.id) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(TheoriaRoomDatabase.DEFAULT_DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.delete(
                "series_entries", "source = ? AND series_address = ? AND address = ?",
                arrayOf(followed.id.source.id, followed.id.address, entries.first().entry.address),
            )
        }
        val left = runBlocking { series.repository.entries(followed.id) }
        assertTrue("The newest chapter is no longer stored", left.none { it.entry.address == entries.first().entry.address })
        live.report("forgotten", "\"${followed.title}\" no longer stores \"${entries.first().entry.name}\"; chapters ${entries.size} -> ${left.size}")
    }

    /** Run after the background check posted a notification: tapping it opens that series' page. */
    @Test
    fun aNewChapterNotificationOpensItsSeries() {
        val followed = runBlocking { series.repository.observeFollowed().first() }.first()
        live.report("shelf", "\"${followed.series.title}\" unread=${followed.unreadCount}")
        device.openNotification()
        val notification = live.waitFor("the series' notification", By.text(followed.series.title))
        live.report("notification", "\"${notification.text}\" is in the shade")
        notification.click()
        live.waitFor("the series' page", By.text(CHAPTER_COUNT), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        live.waitFor("the series' page naming the series", By.text(followed.series.title))
        live.waitFor("the series' page showing it followed", By.text("Following"))
        live.report("opened", "the notification opened \"${followed.series.title}\"'s page")
    }

    /** A notification for one series, tapped while another series' page is open, opens its own series over it. */
    @Test
    fun aNotificationOpensItsSeriesOverAnotherSeriesPage() {
        val (open, announced) = runBlocking { series.sources().first().popular(1) }.series
        runBlocking { series.repository.store(announced) }
        live.openSeriesTab()
        live.tap("the first listed series", By.text(open.title), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        live.waitFor("the first series' chapter count", By.text(CHAPTER_COUNT), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        notifySeriesUpdate(context, SeriesUpdate(announced, listOf(SeriesEntry("/chapter/none", "Ch.1"))))
        device.openNotification()
        live.tap("the other series' notification", By.text(announced.title))
        live.waitFor("the announced series' page", By.text(announced.title), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        live.waitFor("the announced series' chapters", By.text(CHAPTER_COUNT), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        assertNull("The page under it is covered", device.findObject(By.text(open.title)))
        live.report("opened", "over \"${open.title}\"'s page, the notification opened \"${announced.title}\"")
        device.pressBack()
        live.waitFor("the first series' page under it", By.text(open.title))
        live.report("back", "Back returned to \"${open.title}\"")
    }

    @Test
    fun unfollowingTheLastSeriesCancelsTheCheck() {
        val followed = runBlocking { series.repository.observeFollowed().first() }
        assertTrue("Exactly one series is followed", followed.size == 1)
        live.report("job", "before: scheduled every ${awaitCheckJob(scheduled = true)?.intervalMillis?.milliseconds}")
        live.openSeriesTab(view = SeriesLiveDriver.FOLLOWING)
        live.tap("the followed series in Following", By.text(followed.single().series.title))
        live.tap("Following, to unfollow", By.text("Following"))
        live.waitFor("the series to read as not followed", By.text("Follow"))
        awaitCheckJob(scheduled = false)
        live.report("job", "after unfollowing the last series: cancelled")
    }

    /** Opens the chapter's sheet from the dock twice: by a tap, then by the click an accessibility service sends. */
    private fun openInfoFromTheDock() {
        viewerControl("the dock's Info button", By.desc("Info")).click()
        live.waitFor("the chapter's sheet after a tap", By.desc("Start over"))
        assertNull("A chapter's sheet has no tag search", device.findObject(By.text("Your search")))
        device.pressBack()
        val node = viewerControl("the dock's Info button", By.desc("Info")).accessibilityNodeInfo
        assertTrue("Info takes an accessibility click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        live.waitFor("the chapter's sheet after an accessibility click", By.desc("Start over"))
        live.report("info", "a tap and an accessibility click on Info each opened the chapter's sheet, with no tag search")
        device.pressBack()
    }

    /** Follows the series whose page is open, answering the notification question a follow asks while the permission is missing. */
    private fun follow(listed: Series) {
        if (device.wait(Until.hasObject(By.text("Following")), SeriesLiveDriver.SHORT_WAIT_MS) != true) {
            live.tap("Follow", By.text("Follow"))
            // An install that already allowed it is not asked again.
            device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_button")), SeriesLiveDriver.SHORT_WAIT_MS)?.let { allow ->
                live.report("permission", "the follow asked to send notifications")
                allow.click()
            }
        }
        live.waitFor("the series to read as followed", By.text("Following"))
        live.report("follow", "\"${listed.title}\" is followed")
    }

    /** Waits until the background check is [scheduled] or not, and returns it. */
    private fun awaitCheckJob(scheduled: Boolean): JobInfo? {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val deadline = System.currentTimeMillis() + SeriesLiveDriver.UI_TIMEOUT_MS
        while (true) {
            val job = scheduler.getPendingJob(SERIES_UPDATE_JOB_ID)
            if ((job != null) == scheduled) return job
            check(System.currentTimeMillis() < deadline) { "The check stayed ${if (scheduled) "unscheduled" else "scheduled"}" }
            Thread.sleep(SeriesLiveDriver.POLL_MS)
        }
    }

    /**
     * A popular series with more than one chapter, as if never read here: earlier runs and other testers leave read
     * chapters and a reading mode behind on the emulator, so what is stored of its progress is wiped first.
     */
    private fun unreadSeries(source: SeriesSource): Series {
        // A series stored here is counted from storage: the app may be refreshing the page an earlier run left open,
        // and the source refuses two requests for one series at a time.
        val listed = runBlocking {
            source.popular(1).series.take(CANDIDATES).first { listed ->
                val chapters = if (series.repository.series(listed.id) != null) series.repository.entries(listed.id) else source.entries(source.details(listed), stored = emptyList()).entries
                chapters.size > 1
            }
        }
        val key = arrayOf(listed.id.source.id, listed.id.address)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TheoriaRoomDatabase.DEFAULT_DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL("UPDATE series_entries SET read = 0, last_page = 0, page_count = NULL WHERE source = ? AND series_address = ?", key)
            database.execSQL("UPDATE series SET last_opened_entry = NULL, reading_mode = NULL WHERE source = ? AND address = ?", key)
        }
        return listed
    }

    /** The chapter the series page's button opens and the one after it, read from what the page stored. */
    /** Checks the page shows what the source said about [listed]: its status beside the chapter count, its synopsis below. */
    private fun readAbout(listed: Series) {
        // A series opened before shows its stored chapters at once; what its source says about it arrives after.
        val deadline = System.currentTimeMillis() + SeriesLiveDriver.RESULTS_TIMEOUT_MS
        val stored = generateSequence { checkNotNull(runBlocking { series.repository.series(listed.id) }).series }
            .first { (it.description != null || it.status != SeriesStatus.UNKNOWN || System.currentTimeMillis() > deadline).also { told -> if (!told) Thread.sleep(SeriesLiveDriver.POLL_MS) } }
        val description = stored.description
        val shown = description != null && device.wait(Until.hasObject(By.textStartsWith(description.take(DESCRIPTION_START))), SeriesLiveDriver.SHORT_WAIT_MS) == true
        val status = live.waitFor("the chapter count", By.text(CHAPTER_COUNT)).text.substringBeforeLast(" · ", "")
        live.report("about", "status shown \"$status\" (stored ${stored.status}); a description of ${description?.length ?: 0} characters, shown=$shown")
        assertTrue("The page shows a status or a description", status.isNotEmpty() || shown)
    }

    private fun continueChapters(listed: Series): Pair<StoredSeriesEntry, StoredSeriesEntry> = runBlocking {
        val stored = checkNotNull(series.repository.series(listed.id)) { "Opening the series did not store it" }
        val entries = series.repository.entries(listed.id).inReadingOrder()
        val resume = checkNotNull(continueEntry(stored, entries)) { "The series has no chapter to continue" }
        resume to entries[entries.indexOf(resume) + 1]
    }

    /** Searches every source for the first word of [listed]'s title and checks each visible source answers in a group of its own. */
    private fun searchEverySource(source: SeriesSource, listed: Series) {
        val sources = runBlocking { series.sources() }
        val field = live.waitFor("the search field", By.clazz("android.widget.EditText"))
        field.text = listed.title.substringBefore(' ')
        field.click()
        device.pressEnter()
        live.waitFor("a result count beside a source's name", By.text(Pattern.compile("\\d+")), SeriesLiveDriver.RESULTS_TIMEOUT_MS)
        val groups = device.findObjects(By.text(Pattern.compile(sources.map { Pattern.quote(it.name) }.distinct().joinToString("|")))).size
        live.report("search", "sources=${sources.size} groups on screen=$groups, first is ${source.name}")
        assertTrue("Each source answers in its own group", groups > 1 || sources.size == 1)
        live.tap("Clear", By.desc("Clear"))
        live.waitFor("the browse list back", By.text("Browse"))
    }

    /** [selector], after scrolling the list down until something matches it. */
    private fun scrolledTo(selector: BySelector): BySelector {
        repeat(SCROLLS) {
            if (device.wait(Until.hasObject(selector), SeriesLiveDriver.SHORT_WAIT_MS) == true) return selector
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, SWIPE_STEPS)
        }
        return selector
    }

    /** Waits until the series' record of the chapter at [address] satisfies [reached]. */
    private fun awaitProgress(listed: Series, address: String, reached: (StoredSeriesEntry) -> Boolean) {
        val deadline = System.currentTimeMillis() + SeriesLiveDriver.UI_TIMEOUT_MS
        while (true) {
            val entry = runBlocking { series.repository.entries(listed.id) }.first { it.entry.address == address }
            if (reached(entry)) {
                live.report("progress", "stored page ${entry.position} of ${entry.length}, read=${entry.read}")
                return
            }
            check(System.currentTimeMillis() < deadline) { "Progress stayed at page ${entry.position} of ${entry.length}, read=${entry.read}" }
            Thread.sleep(SeriesLiveDriver.POLL_MS)
        }
    }

    /** Opens the page overview, picks whichever sideways mode is not the current one, and returns its label. */
    private fun chooseOtherReadingMode(): String {
        viewerControl("the overview button", By.desc("Open media overview")).click()
        val mode = SIDEWAYS_MODES.first { it != checkedReadingMode() }
        live.tap("the mode \"$mode\"", By.text(mode))
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
        live.waitFor("the reading mode switch", By.text(SIDEWAYS_MODES[0]))
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
            Thread.sleep(SeriesLiveDriver.POLL_MS)
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

    private fun viewerControl(what: String, selector: BySelector): UiObject2 = live.viewerControl(what, selector)

    private companion object {
        val CHAPTER_COUNT: Pattern = Pattern.compile("(.+ · )?\\d+ chapters?")
        const val DESCRIPTION_START = 30
        const val STRIP_TAPS = 5

        /** The page the test stops on, far enough in that resuming there cannot be the start by accident. */
        const val TARGET_PAGE = 3

        /** How many popular series are tried for one with more than one chapter. */
        const val CANDIDATES = 10
        val SIDEWAYS_MODES = listOf("Left to right", "Right to left")
        const val VERTICAL = "Vertical"
        val READING_MODES = SIDEWAYS_MODES + VERTICAL
        val PAGE_TILE: BySelector = By.descStartsWith("Go to page ")
        const val SCROLLS = 6
        const val END_PUSHES = 60

        /** A drag this short (under the Viewer's 64 dp) and this slow scrolls a vertical chapter but cannot leave it. */
        const val NUDGE_DP = 40
        const val NUDGE_STEPS = 40
        const val NUDGES = 12

        /** A tap can land while the controls are still appearing, so hiding them is tried more than once. */
        const val CONTROL_TAPS = 3
        const val SWIPE_STEPS = 20

    }
}
