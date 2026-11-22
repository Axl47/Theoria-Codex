package com.theoriacodex.app.live

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.theoriacodex.app.di.SeriesDependencies
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.ui.TopLevelDestination
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * What the live Series tests share: the real debug app's Series dependencies, and a hand on its screen that reads
 * and reports text only, so no provider media leaves the device. The tests talk to live sites, so each one starts
 * with [requireOptIn].
 */
internal class SeriesLiveDriver {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    val context: Context = ApplicationProvider.getApplicationContext()
    val container: TheoriaAppContainer by lazy { runBlocking { (context as TheoriaAppContainerOwner).awaitAppContainer() } }
    val series: SeriesDependencies by lazy { checkNotNull(container.series) { "This build has no extension host" } }

    fun requireOptIn() {
        val enabled = InstrumentationRegistry.getArguments().getString(OPT_IN)?.toBooleanStrictOrNull() ?: false
        assumeTrue("The live Series tests require $OPT_IN=true", enabled)
    }

    /** Launches the app afresh on the Series tab, which also proves the tab is in the bar, and waits for [shown]. */
    fun openSeriesTab(shown: BySelector = By.text("Browse")) {
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        tap("the Series tab", By.desc(TopLevelDestination.Series.label), LAUNCH_TIMEOUT_MS)
        waitFor("the Series tab's content", shown, RESULTS_TIMEOUT_MS)
    }

    fun waitFor(what: String, selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs) ?: error("Timed out waiting for $what; on screen: ${screenText()}")

    fun tap(what: String, selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS) = waitFor(what, selector, timeoutMs).click()

    /** Waits until [read] gives a value [done] accepts, and returns it. */
    fun <T> await(what: String, read: suspend () -> T, done: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (true) {
            val value = runBlocking { read() }
            if (done(value)) return value
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what; last: $value" }
            Thread.sleep(POLL_MS)
        }
    }

    /** The labels on screen, for a failure message; text only. */
    fun screenText(): String = device.findObjects(By.pkg(context.packageName))
        // The screen keeps changing while it is read; an element that has gone is skipped.
        .flatMap { element -> runCatching { listOfNotNull(element.text, element.contentDescription) }.getOrDefault(emptyList()) }
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" | ")
        .take(SCREEN_TEXT_CHARS)

    /** Shown by `am instrument -r` as it happens. */
    fun report(step: String, text: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("series", "$step: $text") })
    }

    companion object {
        const val OPT_IN = "extensionSourceLive"
        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val RESULTS_TIMEOUT_MS = 45_000L
        const val UI_TIMEOUT_MS = 30_000L
        const val SHORT_WAIT_MS = 2_000L
        const val POLL_MS = 200L
        private const val SCREEN_TEXT_CHARS = 600
    }
}
