package com.theoriacodex.app.live

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import coil.Coil
import coil.EventListener
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.search.LAST_ACTIVE_QUERY_KEY
import com.theoriacodex.app.search.defaultQuery
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves a Mihon extension end to end in the real debug app: its source lists series on Search, and opening one shows
 * a chapter's pages in the Viewer. It talks to the live site, so it runs only with `-e extensionSourceLive true`,
 * after an extension file has been pushed to the app's `mihon-extensions` folder (see `mihon-host/README.md`).
 *
 * The report is counts and labels only, never images: provider media stays off screenshots and logs.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionSourceLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun requireExplicitOptIn() {
        val enabled = InstrumentationRegistry.getArguments().getString(OPT_IN)?.toBooleanStrictOrNull() ?: false
        assumeTrue("The extension source test requires $OPT_IN=true", enabled)
    }

    @Test
    fun aSeriesFromAnExtensionOpensInTheViewer() {
        val container = runBlocking { (context as TheoriaAppContainerOwner).awaitAppContainer() }
        val source = runBlocking {
            withTimeout(LOAD_TIMEOUT_MS) {
                container.sources.availableSources.first { sources -> sources.any { it.id.startsWith(EXTENSION_PREFIX) } }
            }.first { it.id.startsWith(EXTENSION_PREFIX) }
        }
        report("source", "${source.displayName()} is available as ${source.id}")

        val queries = container.data.queryRepository
        val tabs = container.data.uiRestoreRepository
        val previousQuery = runBlocking { queries.observeAppliedQuery(LAST_ACTIVE_QUERY_KEY).first() }
        val previousTab = runBlocking { tabs.getLastTab() }
        val images = LoadedImages()
        // Images kept from an earlier run would be shown without asking the extension for them.
        context.imageLoader.run {
            memoryCache?.clear()
            diskCache?.clear()
        }
        Coil.setImageLoader(context.imageLoader.newBuilder().eventListener(images).build())
        runBlocking {
            queries.upsertAppliedQuery(LAST_ACTIVE_QUERY_KEY, defaultQuery(QueryMode.Source(source)).copy(sort = SortMode.POPULAR))
            tabs.setLastTab(TopLevelDestination.Search.route)
        }
        try {
            openFirstSeries(source.displayName(), images)
        } finally {
            runBlocking {
                queries.upsertAppliedQuery(LAST_ACTIVE_QUERY_KEY, previousQuery ?: defaultQuery())
                previousTab?.let { tabs.setLastTab(it) }
            }
        }
    }

    private fun openFirstSeries(label: String, images: LoadedImages) {
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        requireNotNull(device.wait(Until.findObject(By.desc(SEARCH_ONLY_CONTROL)), LAUNCH_TIMEOUT_MS)) { "Search did not open" }
        assertTrue(
            "Search does not name $label as its source; on screen: ${screenText()}",
            device.wait(Until.hasObject(By.descContains(label)), UI_TIMEOUT_MS),
        )
        // A source lists nothing until a search is sent; one with no tags asks for its popular series.
        requireNotNull(device.findObject(By.text(SEARCH_INPUT))) { "No search box; on screen: ${screenText()}" }.click()
        device.pressEnter()

        requireNotNull(device.wait(Until.findObject(CARD), RESULTS_TIMEOUT_MS)) {
            "The source listed no series; on screen: ${screenText()}"
        }
        await("a cover image", images) { images.loaded() > 0 }
        val covers = images.addresses()
        report("search", "cards=${device.findObjects(CARD).size} covers=${covers.size} failed=${images.failed()}")

        device.findObject(CARD).click()
        val pageCount = firstPageLabel().removePrefix(FIRST_PAGE).toInt()
        await("a page image", images) { (images.addresses() - covers).isNotEmpty() }
        report("viewer", "pages=$pageCount pageImages=${(images.addresses() - covers).size} failures=${images.failures()}")
        assertTrue("A chapter has more than one page", pageCount > 1)
    }

    /**
     * The page strip's label for the open post's first page. The Viewer hides its controls after a few seconds, often
     * before a chapter's pages have arrived, so each wait that finds no strip taps the page to bring them back.
     */
    private fun firstPageLabel(): String {
        val deadline = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            device.wait(Until.findObject(By.descStartsWith(FIRST_PAGE)), CONTROLS_WAIT_MS)?.let { return it.contentDescription }
            device.click(device.displayWidth / 2, device.displayHeight / 2)
        }
        error("The Viewer showed no pages; on screen: ${screenText()}")
    }

    private fun await(what: String, images: LoadedImages, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (!done()) {
            check(System.currentTimeMillis() < deadline) {
                "Timed out waiting for $what; image failures: ${images.failures()}; on screen: ${screenText()}"
            }
            Thread.sleep(POLL_MS)
        }
    }

    /** The labels on screen, for a failure message; text only, so no provider media leaves the device. */
    private fun screenText(): String = device.findObjects(By.pkg(context.packageName))
        .flatMap { listOfNotNull(it.text, it.contentDescription) }
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" | ")
        .take(SCREEN_TEXT_CHARS)

    private fun report(step: String, text: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("extension", "$step: $text") })
    }

    /** Which images the app finished loading, by address, so pages can be told from the covers loaded before them. */
    private class LoadedImages : EventListener {
        private val loaded = ConcurrentHashMap.newKeySet<String>()
        private val failures = ConcurrentHashMap<String, String>()

        fun loaded(): Int = loaded.size

        fun failed(): Int = failures.size

        fun failures(): String = "${failures.size} ${failures.values.distinct().take(FAILURES_SHOWN)}"

        fun addresses(): Set<String> = loaded.toSet()

        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            loaded += request.data.toString()
        }

        override fun onError(request: ImageRequest, result: ErrorResult) {
            val address = request.data.toString()
            // Which file failed, without its full address: the site and the file type are what matter here.
            val file = "${address.substringAfter("://").substringBefore('/')} .${address.substringBefore('#').substringAfterLast('.')}"
            failures[address] = "$file: ${result.throwable.toString().take(FAILURE_CHARS)}"
        }
    }

    private companion object {
        const val OPT_IN = "extensionSourceLive"
        const val EXTENSION_PREFIX = "custom_mihon_"
        const val SEARCH_ONLY_CONTROL = "Filter and sort"
        const val SEARCH_INPUT = "Search tags"
        const val FIRST_PAGE = "Go to page 1 of "
        val CARD = By.clickable(true).longClickable(true)

        const val LOAD_TIMEOUT_MS = 30_000L
        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val RESULTS_TIMEOUT_MS = 45_000L
        const val RESOLVE_TIMEOUT_MS = 45_000L
        const val UI_TIMEOUT_MS = 30_000L
        const val POLL_MS = 100L
        const val CONTROLS_WAIT_MS = 2_500L
        const val SCREEN_TEXT_CHARS = 600
        const val FAILURES_SHOWN = 3
        const val FAILURE_CHARS = 80
    }
}
