package com.theoriacodex.app.live

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.theoriacodex.app.di.SeriesDependencies
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.ui.TopLevelDestination
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

internal class SeriesLiveDriver {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    val context: Context = ApplicationProvider.getApplicationContext()
    val container: TheoriaAppContainer by lazy { runBlocking { (context as TheoriaAppContainerOwner).awaitAppContainer() } }
    val series: SeriesDependencies by lazy { checkNotNull(container.series) { "This build has no extension host" } }
    val targetPackage: String = InstrumentationRegistry.getArguments().getString(TARGET_PACKAGE) ?: context.packageName

    val targetIsThisApp: Boolean get() = targetPackage == context.packageName

    fun requireOptIn() {
        val enabled = InstrumentationRegistry.getArguments().getString(OPT_IN)?.toBooleanStrictOrNull() ?: false
        assumeTrue("The live Series tests require $OPT_IN=true", enabled)
    }

    fun openSeriesTab(shown: BySelector = By.text(BROWSE), view: String? = BROWSE) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        device.executeShellCommand("am start -W -n $targetPackage/$MAIN_ACTIVITY -f $flags")
        tap("the Series tab", By.desc(TopLevelDestination.Series.label), LAUNCH_TIMEOUT_MS)
        if (view != null) tap("the $view view", By.text(view), RESULTS_TIMEOUT_MS)
        waitFor("the Series tab's content", shown, RESULTS_TIMEOUT_MS)
    }

    fun waitFor(what: String, selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs) ?: error("Timed out waiting for $what; on screen: ${screenText()}")

    fun tap(what: String, selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS) = try {
        waitFor(what, selector, timeoutMs).click()
    } catch (_: StaleObjectException) {
        waitFor(what, selector, timeoutMs).click()
    }

    fun <T> await(what: String, read: suspend () -> T, done: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (true) {
            val value = runBlocking { read() }
            if (done(value)) return value
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what; last: $value" }
            Thread.sleep(POLL_MS)
        }
    }

    fun screenText(): String = device.findObjects(By.pkg(targetPackage))
        .flatMap { element -> runCatching { listOfNotNull(element.text, element.contentDescription) }.getOrDefault(emptyList()) }
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" | ")
        .take(SCREEN_TEXT_CHARS)

    fun viewerControl(what: String, selector: BySelector, timeoutMs: Long = RESULTS_TIMEOUT_MS): UiObject2 {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            device.wait(Until.findObject(selector), CONTROLS_WAIT_MS)?.let { return it }
            device.click(device.displayWidth / 2, device.displayHeight / 2)
        }
        error("The Viewer did not show $what; on screen: ${screenText()}")
    }

    fun report(step: String, text: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("series", "$step: $text") })
    }

    companion object {
        const val OPT_IN = "extensionSourceLive"
        const val BROWSE = "Browse"
        const val FOLLOWING = "Following"
        const val TARGET_PACKAGE = "targetPackage"
        private const val MAIN_ACTIVITY = "com.theoriacodex.app.MainActivity"
        const val CONTROLS_WAIT_MS = 2_500L
        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val RESULTS_TIMEOUT_MS = 45_000L
        const val UI_TIMEOUT_MS = 30_000L
        const val SHORT_WAIT_MS = 2_000L
        const val POLL_MS = 200L
        private const val SCREEN_TEXT_CHARS = 600
    }
}
