package com.theoriacodex.app.live

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import coil.Coil
import coil.EventListener
import coil.decode.DataSource
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.media.MediaCacheMaintenance
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.app.media.previewMediaDeliveryPlan
import com.theoriacodex.app.media.viewerMediaDeliveryPlan
import com.theoriacodex.app.search.LAST_ACTIVE_QUERY_KEY
import com.theoriacodex.app.search.buildFeedImageRequest
import com.theoriacodex.app.search.defaultQuery
import com.theoriacodex.app.search.feedPreviewDecodeSize
import com.theoriacodex.app.search.previewAspectRatio
import com.theoriacodex.app.search.state.modeKey
import com.theoriacodex.app.viewer.ViewerMediaOverviewKind
import com.theoriacodex.app.viewer.buildViewerImageRequest
import com.theoriacodex.app.viewer.viewerMediaOverviewItems
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures how long the real search and image paths take from a fresh process, against the installed Debug app's
 * accounts and live feeds. One instrumentation run measures one case, because connection pools live and die with the
 * process, so every case starts with no open connections. `scripts/live_latency_bench.py` passes
 * `theoriaLiveLatency=true` and the case; the default test lane skips this class.
 *
 * - `source:<ID>` searches one source, loads its first screen of card images, opens the first posts' Viewer images
 *   and, for multi-page posts, the page thumbnails the scrubber and gallery grid use.
 * - `unified:cold` opens the real Search tab with empty image caches and a filterless unified query.
 *   `unified:warm` does the same keeping every cache, as reopening the app does.
 *
 * It reports numbers only and drives the app through accessibility nodes, so no provider media is captured or logged.
 * Byte counts are the app's whole traffic over the step, read from the kernel, so they include TLS and headers.
 */
@RunWith(AndroidJUnit4::class)
class LiveLatencyBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun requireExplicitOptIn() {
        val enabled = InstrumentationRegistry.getArguments().getString(OPT_IN)?.toBooleanStrictOrNull() ?: false
        assumeTrue("The live latency benchmark requires $OPT_IN=true", enabled)
    }

    @Test
    fun measureCase() {
        val case = requireNotNull(InstrumentationRegistry.getArguments().getString(CASE)) { "Pass $CASE" }
        val report = when {
            case.startsWith(SOURCE_PREFIX) -> runBlocking { measureSource(case.removePrefix(SOURCE_PREFIX)) }
            case == UNIFIED_COLD -> measureUnified(clearCaches = true)
            case == UNIFIED_WARM -> measureUnified(clearCaches = false)
            else -> error("Unknown case $case")
        }.put("case", case)
        instrumentation.sendStatus(0, Bundle().apply { putString(REPORT, report.toString()) })
    }

    private suspend fun container(): TheoriaAppContainer = (context as TheoriaAppContainerOwner).awaitAppContainer()

    private suspend fun clearDisposableCaches(container: TheoriaAppContainer) =
        MediaCacheMaintenance(context, container.data.cacheRepository, container.sources.pixivUgoiraClient).clear()

    private suspend fun measureSource(id: String): JSONObject = withContext(Dispatchers.IO) {
        val container = container()
        val registry = container.sources.registry
        val source = registry.availableSources().firstOrNull { it.id == id }
            ?: return@withContext JSONObject().put("unavailable", true)
        val adapter = requireNotNull(registry.adapterFor(source))
        clearDisposableCaches(container)
        val report = JSONObject()
        val query = defaultQuery(QueryMode.Source(source))

        val rxBeforeSearch = settledRxBytes()
        val searchStart = clock()
        val first = runCatching {
            withTimeout(SEARCH_TIMEOUT_MS) { registry.unifiedOrchestrator().searchSource(adapter, query, null) }
        }
        report.put("search_ms", clock() - searchStart)
        val page = first.getOrElse { error ->
            return@withContext report.put("search_ok", false)
                .put("failure", (error as? SourceAdapterException)?.reason?.name ?: error.javaClass.simpleName)
        }
        report.put("search_ok", true).put("posts", page.items.size).put("search_rx", settledRxBytes() - rxBeforeSearch)

        page.nextPageToken?.let { token ->
            val nextStart = clock()
            runCatching { withTimeout(SEARCH_TIMEOUT_MS) { registry.unifiedOrchestrator().searchSource(adapter, query, token) } }
                .onSuccess { report.put("next_page_ms", clock() - nextStart) }
        }

        val cards = page.items.take(CARD_COUNT)
        loadAll("cards", cards.mapNotNull { cardRequest(it, source) }, report)
        measureViewer(cards, source, report, adapter::resolvePost)
        report
    }

    private fun cardRequest(post: Post, source: SourceKey): ImageRequest? {
        val location = previewMediaDeliveryPlan(post).primary?.location ?: return null
        val size = feedPreviewDecodeSize(context.resources.displayMetrics.widthPixels, previewAspectRatio(post))
        return buildFeedImageRequest(context, location, source, size)
    }

    /** Resolves the first posts as opening them would, then loads their first Viewer images and any page thumbnails. */
    private suspend fun measureViewer(
        cards: List<Post>,
        source: SourceKey,
        report: JSONObject,
        resolve: suspend (com.theoriacodex.domain.model.PostId) -> Post?,
    ) {
        val resolveStart = clock()
        val resolved = cards.take(VIEWER_POSTS).mapNotNull { post -> runCatching { resolve(post.id) }.getOrNull() }
        report.put("resolve_ms", clock() - resolveStart).put("resolved", resolved.size)
        val viewerRequests = resolved.mapNotNull { post ->
            val media = postMediaItems(post).firstOrNull() ?: return@mapNotNull null
            viewerMediaDeliveryPlan(post, media).primary?.location?.let { buildViewerImageRequest(context, it, source) }
        }
        loadAll("viewer", viewerRequests, report)
        val gallery = resolved.firstOrNull { post -> viewerMediaOverviewItems(post).size >= MIN_GALLERY_PAGES } ?: return
        val pages = viewerMediaOverviewItems(gallery)
            .filter { it.kind == ViewerMediaOverviewKind.STILL_IMAGE }
            .take(GALLERY_PAGES)
            .mapNotNull { item ->
                item.thumbnailLocation?.let {
                    buildViewerImageRequest(context, it, source, targetWidthPx = PAGE_EDGE_PX, targetHeightPx = PAGE_EDGE_PX)
                }
            }
        loadAll("pages", pages, report)
    }

    /** Runs [requests] at once on the app's real image loader and reports when they finished and what they cost. */
    private suspend fun loadAll(label: String, requests: List<ImageRequest>, report: JSONObject) = coroutineScope {
        if (requests.isEmpty()) return@coroutineScope
        val rxBefore = settledRxBytes()
        val start = clock()
        val results = requests.map { request ->
            async {
                val result = context.imageLoader.execute(request)
                (clock() - start) to (result is SuccessResult)
            }
        }.awaitAll()
        val finished = results.filter { it.second }.map { it.first }.sorted()
        report.put("${label}_count", requests.size).put("${label}_ok", finished.size)
        if (finished.isNotEmpty()) {
            report.put("${label}_first_ms", finished.first())
                .put("${label}_median_ms", finished[finished.size / 2])
                .put("${label}_all_ms", finished.last())
        }
        report.put("${label}_rx", settledRxBytes() - rxBefore)
    }

    private fun measureUnified(clearCaches: Boolean): JSONObject {
        val events = CardImageEvents()
        val owner = runBlocking { container() }
        val queries = owner.data.queryRepository
        val keys = listOf(LAST_ACTIVE_QUERY_KEY, modeKey(QueryMode.Unified))
        val previous: List<Pair<String, Query>> = runBlocking {
            if (clearCaches) clearDisposableCaches(owner)
            val saved = keys.mapNotNull { key -> queries.observeAppliedQuery(key).first()?.let { key to it } }
            keys.forEach { key -> queries.upsertAppliedQuery(key, defaultQuery()) }
            saved
        }
        try {
            return driveSearchTab(events)
        } finally {
            runBlocking { previous.forEach { (key, query) -> queries.upsertAppliedQuery(key, query) } }
        }
    }

    private fun driveSearchTab(events: CardImageEvents): JSONObject {
        Coil.setImageLoader(context.imageLoader.newBuilder().eventListener(events).build())
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val searchTab = requireNotNull(device.wait(Until.findObject(By.desc(SEARCH_TAB)), LAUNCH_TIMEOUT_MS)) {
            "The app shell did not open"
        }
        val report = JSONObject()
        val rxBefore = settledRxBytes()
        // Whatever Home loaded before the tap is not Search's work.
        events.reset()
        val start = clock()
        searchTab.click()
        // Home's own cards must not count, so wait for a control only Search has.
        device.wait(Until.hasObject(By.desc(SEARCH_ONLY_CONTROL)), ROUTE_TIMEOUT_MS)
        report.put("first_card_ms", awaitFirstCard(start))
        awaitImages(events, FIRST_SCREEN_IMAGES)
        report.put("first_image_ms", events.nthFinishedAt(1)?.minus(start))
            .put("screen_images_ms", events.nthFinishedAt(FIRST_SCREEN_IMAGES)?.minus(start))
            .put("cards_visible", device.findObjects(CARD).size)
            .put("first_screen_rx", settledRxBytes() - rxBefore)
        measureScroll(events, report)
        return report
    }

    private fun awaitFirstCard(start: Long): Long? {
        while (clock() - start < RESULTS_TIMEOUT_MS) {
            if (device.findObject(CARD) != null) return clock() - start
            SystemClock.sleep(CARD_POLL_MS)
        }
        return null
    }

    private fun awaitImages(events: CardImageEvents, count: Int) {
        val deadline = clock() + RESULTS_TIMEOUT_MS
        while (events.finishedCount() < count && clock() < deadline) SystemClock.sleep(CARD_POLL_MS)
    }

    /** A card waiting less means its image was already on its way or on disk when it scrolled into view. */
    private fun measureScroll(events: CardImageEvents, report: JSONObject) {
        SystemClock.sleep(SCROLL_SETTLE_MS)
        events.reset()
        repeat(FLINGS) {
            largestScrollable()?.apply { setGestureMargin(device.displayWidth / 6) }?.fling(Direction.DOWN)
            SystemClock.sleep(SCROLL_SETTLE_MS)
        }
        val waits = events.waits().sorted()
        report.put("scroll_images", waits.size).put("scroll_failed", events.failedCount())
        if (waits.isNotEmpty()) {
            report.put("scroll_wait_p50_ms", waits[waits.size / 2]).put("scroll_wait_p90_ms", waits[waits.size * 9 / 10])
        }
        DataSource.entries.forEach { source -> report.put("scroll_${source.name.lowercase()}", events.countFrom(source)) }
    }

    private fun largestScrollable(): UiObject2? =
        device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() * it.visibleBounds.width() }

    private fun clock(): Long = SystemClock.elapsedRealtime()

    /** The kernel's per-app counters trail the socket slightly, so each read waits for them. */
    private fun settledRxBytes(): Long {
        SystemClock.sleep(RX_SETTLE_MS)
        return TrafficStats.getUidRxBytes(Process.myUid())
    }

    /** Times every card image the screen itself requests. Preload requests are not cards waiting to be seen. */
    private class CardImageEvents : EventListener {
        private class Sample(val finishedAt: Long, val waitMs: Long, val source: DataSource)

        private val started = ConcurrentHashMap<ImageRequest, Long>()
        private val samples = ConcurrentLinkedQueue<Sample>()
        private val failures = ConcurrentLinkedQueue<Long>()

        fun reset() {
            samples.clear()
            failures.clear()
        }

        private fun ImageRequest.isCard() =
            data is String && precision == Precision.INEXACT && parameters.value<Boolean>(PRELOAD_PARAMETER) != true

        override fun onStart(request: ImageRequest) {
            if (request.isCard()) started[request] = SystemClock.elapsedRealtime()
        }

        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            val startedAt = started.remove(request) ?: return
            val now = SystemClock.elapsedRealtime()
            samples += Sample(now, now - startedAt, result.dataSource)
        }

        override fun onError(request: ImageRequest, result: ErrorResult) {
            if (started.remove(request) != null) failures += SystemClock.elapsedRealtime()
        }

        fun finishedCount(): Int = samples.size
        fun failedCount(): Int = failures.size
        fun nthFinishedAt(n: Int): Long? = samples.map { it.finishedAt }.sorted().getOrNull(n - 1)
        fun waits(): List<Long> = samples.map { it.waitMs }
        fun countFrom(source: DataSource): Int = samples.count { it.source == source }
    }

    private companion object {
        const val OPT_IN = "theoriaLiveLatency"
        const val CASE = "theoriaCase"
        const val REPORT = "theoriaLatency"
        const val SOURCE_PREFIX = "source:"
        const val UNIFIED_COLD = "unified:cold"
        const val UNIFIED_WARM = "unified:warm"
        const val PRELOAD_PARAMETER = "theoria.feedPreload"
        const val SEARCH_TAB = "Search"
        const val SEARCH_ONLY_CONTROL = "Filter and sort"
        val CARD = By.clickable(true).longClickable(true)

        const val CARD_COUNT = 12
        const val FIRST_SCREEN_IMAGES = 6
        const val VIEWER_POSTS = 4
        const val MIN_GALLERY_PAGES = 8
        const val GALLERY_PAGES = 24
        const val PAGE_EDGE_PX = 360
        const val FLINGS = 6

        const val SEARCH_TIMEOUT_MS = 45_000L
        const val RESULTS_TIMEOUT_MS = 45_000L
        const val LAUNCH_TIMEOUT_MS = 30_000L
        const val ROUTE_TIMEOUT_MS = 5_000L
        const val CARD_POLL_MS = 25L
        const val SCROLL_SETTLE_MS = 3_000L
        const val RX_SETTLE_MS = 1_000L
    }
}
