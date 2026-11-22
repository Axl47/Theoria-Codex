package com.theoriacodex.app.live

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
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.continueEntry
import com.theoriacodex.domain.series.Series
import java.util.regex.Pattern
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
 * The two tests are one story across an app restart, so run them as two `am instrument` calls, each of which starts
 * the app's process anew: first [aSeriesIsBrowsedReadResumedAndFollowed], then [aFollowedSeriesIsOnTheShelfAfterARestart].
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
        val listed = runBlocking { source.popular(1) }.series.first()
        report("source", "${source.name} [${source.language}] lists \"${listed.title}\" first")

        openSeriesTab()
        tap("the listed series", By.text(listed.title), RESULTS_TIMEOUT_MS)
        val chapters = waitFor("the chapter count", By.text(CHAPTER_COUNT), RESULTS_TIMEOUT_MS).text.substringBefore(' ').toInt()
        report("series", "chapters=$chapters")
        assertTrue("A series lists more than one chapter", chapters > 1)

        val chapter = continueChapter(listed)
        tap("the button that starts reading", By.text(Pattern.compile("Start|Continue · .*")))
        viewerControl("the chapter's name as the Viewer's title", By.text("${listed.title} · ${chapter.entry.name}"))
        val pages = pageTile(1).contentDescription.substringAfterLast(' ').toInt()
        // The controls are showing here; a chapter is not a post to like or save to a Codex.
        assertNull("A chapter offers no Like", device.findObject(By.desc("Like post")))
        assertNull("A chapter offers no Save", device.findObject(By.desc("Save to Codex")))
        report("viewer", "title names \"${chapter.entry.name}\" pages=$pages")
        assertTrue("A chapter has pages", pages > 0)

        val target = minOf(TARGET_PAGE, pages)
        if (target > 1) pageTile(target).click()
        awaitProgress(listed, chapter.entry.address, target)
        device.pressBack()

        tap("Continue naming the chapter left unfinished", By.text("Continue · ${chapter.entry.name}"))
        // The top bar counts the page on screen; the chapter opens on its first page until its pages arrive.
        viewerControl("page $target as the page Continue resumes on", By.textEndsWith("$target / $pages"))
        report("resume", "Continue reopened \"${chapter.entry.name}\" on page $target of $pages")
        device.pressBack()

        if (device.wait(Until.hasObject(By.text("Following")), SHORT_WAIT_MS) != true) tap("Follow", By.text("Follow"))
        waitFor("the series to read as followed", By.text("Following"))
        report("follow", "\"${listed.title}\" is followed")
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

    /** Launches the app afresh on the Series tab, which also proves the tab is in the bar. */
    private fun openSeriesTab() {
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        tap("the Series tab", By.desc(TopLevelDestination.Series.label), LAUNCH_TIMEOUT_MS)
        waitFor("the Series tab's source list", By.text("Browse"), RESULTS_TIMEOUT_MS)
    }

    /** The chapter the series page's button opens, read from what the page stored. */
    private fun continueChapter(listed: Series): StoredSeriesEntry = runBlocking {
        val stored = checkNotNull(series.repository.series(listed.id)) { "Opening the series did not store it" }
        checkNotNull(continueEntry(stored, series.repository.entries(listed.id))) { "The series has no chapter to continue" }
    }

    /** Waits until the series has recorded [page] of the chapter at [address]. */
    private fun awaitProgress(listed: Series, address: String, page: Int) {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (true) {
            val entry = runBlocking { series.repository.entries(listed.id) }.first { it.entry.address == address }
            if (entry.lastPage == page) {
                report("progress", "stored page ${entry.lastPage} of ${entry.pageCount}, read=${entry.read}")
                assertTrue("The chapter's page count is stored with its progress", entry.pageCount != null)
                return
            }
            check(System.currentTimeMillis() < deadline) { "Progress stayed at page ${entry.lastPage}, expected $page" }
            Thread.sleep(POLL_MS)
        }
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

        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val RESULTS_TIMEOUT_MS = 45_000L
        const val UI_TIMEOUT_MS = 30_000L
        const val SHORT_WAIT_MS = 2_000L
        const val POLL_MS = 200L
        const val CONTROLS_WAIT_MS = 2_500L
        const val SCREEN_TEXT_CHARS = 600
    }
}
