package com.theoriacodex.app.journeys

import com.theoriacodex.app.library.LIBRARY_SETTINGS_TAG
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.library.librarySectionTag
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.fixtures.JourneyTestActivity
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule

/** Only external provider input and storage location are fixtures; screens and owners are real. */
abstract class AppJourneyFixture {
    @get:Rule val compose = createEmptyComposeRule()
    protected lateinit var container: JourneyAppContainer
    protected lateinit var posts: Map<SourceKey, List<Post>>
    protected lateinit var scenario: ActivityScenario<JourneyTestActivity>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var directory: File

    @Before
    fun prepareFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.theoriacodex.debug")
        directory = File(context.cacheDir, "journey-${UUID.randomUUID()}").apply { mkdirs() }
        posts = fixturePosts(solidImage("landscape", Color.CYAN))
        openGraph()
    }

    /**
     * The provider posts every source serves, each in its display order. Sources in [providerShapedSources] go
     * through a real parser and need posts it can carry (`providerShapedPosts`).
     */
    protected open fun fixturePosts(media: ImageRef): Map<SourceKey, List<Post>> =
        listOf(SourceKey.PIXIV, SourceKey.GELBOORU).associateWith { source ->
            (0 until 60).map { index ->
                Post(
                    id = PostId(source, "journey-$index"), preview = media, full = media,
                    pageUrl = null, width = 320, height = 320,
                    canonicalTags = listOf("landscape", if (index % 2 == 0) "blue" else "green", "multi",
                        if (source == SourceKey.PIXIV) "pixiv_seed" else "gelbooru_seed"),
                    rawTags = emptyList(), authorName = "Journey artist", createdAtEpochMs = 1_000L + index,
                    title = "${source.id} landscape $index",
                    creatorProfile = CreatorProfile(source, "Journey artist", "journey-artist", uploadsQuery = "journey-artist"),
                ).let { customizePost(index, it) }
            }
        }

    /** Sources served as provider JSON through a real adapter instead of as ready-made posts. */
    protected open val providerShapedSources: Set<SourceKey> = emptySet()

    /** Lets a journey reshape a fixture post before the graph reads it. */
    protected open fun customizePost(index: Int, post: Post): Post = post

    /** A one-color image on disk, standing in for provider media. */
    protected fun solidImage(name: String, color: Int): ImageRef {
        val image = directory.resolve("$name.png")
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return ImageRef(null, image.absolutePath, "image/png")
    }

    /**
     * A patterned image on disk, addressed by a file URL as provider media is by its URL, so the app hashes its picture
     * ([com.theoriacodex.app.di.ListingPictures]); solid images are too flat to hash.
     */
    protected fun patternImage(name: String): ImageRef {
        val image = directory.resolve("$name.png")
        val size = 320
        val pixels = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            Color.rgb((x * 7 + y * 3) % 256, (x * y / 64) % 256, if ((x / 40 + y / 40) % 2 == 0) 40 else 220)
        }
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return ImageRef(Uri.fromFile(image).toString(), null, "image/png")
    }

    private fun openGraph() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        container = io {
            JourneyAppContainer(context, directory, posts, scope, pageSize = 20, providerShapedSources = providerShapedSources)
                .also { it.awaitReady() }
        }
    }

    /** New Activity, ViewModels, repositories and Room connection; only on-disk fixture data survives. */
    protected fun coldGraphRelaunch() {
        scenario.close()
        JourneyTestActivity.fixtureContainer = null
        io { container.shutdown() }
        openGraph()
        launch()
    }

    protected fun launch() {
        JourneyTestActivity.fixtureContainer = container
        scenario = ActivityScenario.launch(JourneyTestActivity::class.java)
        waitFor("app shows its Search tab after launch", timeoutMillis = 15_000) {
            compose.onNodeWithContentDescription("Search").isDisplayed()
        }
    }

    protected fun tab(label: String) {
        activate(compose.onNodeWithContentDescription(label))
        compose.waitForIdle()
    }

    /** Shows Library at [section]. */
    protected fun library(section: LibrarySection) {
        tab("Library")
        activate(compose.onNodeWithTag(librarySectionTag(section)))
        compose.waitForIdle()
    }

    /** Opens Settings from Library's gear. */
    protected fun openSettings() {
        tab("Library")
        activate(compose.onNodeWithTag(LIBRARY_SETTINGS_TAG))
        compose.waitForIdle()
    }

    protected fun openRecentsSection(label: String) {
        activate(compose.onNodeWithContentDescription("Filter History by section"))
        activate(compose.onNodeWithText(label))
        compose.waitForIdle()
    }

    /**
     * Opens [source]'s fixture post at [index] in the Viewer, from a Recents search for [tags] recorded as
     * [recentSearch]; the post must show near the top of that search's results.
     */
    protected fun openSearchPost(
        index: Int,
        recentSearch: String,
        source: SourceKey = SourceKey.PIXIV,
        tags: List<String> = listOf("multi"),
    ) {
        io {
            container.data.recentsRepository.recordSearch(
                Query(QueryMode.Source(source), tags, emptyList(), SortMode.POPULAR, null, null),
                recentSearch,
            )
        }
        launch()
        library(LibrarySection.History)
        openRecentsSection("Searches")
        activate(compose.onNodeWithTag("Recent search:$recentSearch"))
        val card = compose.onNodeWithTag(searchCardTestTag(posts.getValue(source)[index].id))
        waitFor("search result card $index for \"$recentSearch\" is displayed") { card.isDisplayed() }
        activate(card)
    }

    /** Exercise the visible control's action without racing its animated touch coordinates. */
    protected fun activate(node: SemanticsNodeInteraction) {
        waitForNode(node, "displayed and enabled") { node.isDisplayed() && isEnabled().matches(node.fetchSemanticsNode()) }
        node.performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Long-press a control once it has loaded, through its semantics action rather than injected touch. */
    protected fun longPress(node: SemanticsNodeInteraction) {
        waitForNode(node, "displayed for a long press") { node.isDisplayed() }
        node.performSemanticsAction(SemanticsActions.OnLongClick)
    }

    /** Types [value] into the open Search composer and adds it as a tag. */
    /** Runs the composer's query now and closes it, through the keyboard's Search action. */
    protected fun submitSearch() {
        val input = compose.onNodeWithTag("Search query input")
        waitFor("Search query input is displayed") { input.isDisplayed() }
        input.performImeAction()
    }

    protected fun addSearchTag(value: String) {
        val input = compose.onNodeWithTag("Search query input")
        waitFor("Search query input is empty before typing \"$value\"") {
            input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty()
        }
        activate(input)
        input.performTextInput(value)
        val add = hasAnyDescendant(hasText("Add", substring = false)) and
            isEnabled() and hasClickAction() and !hasSetTextAction()
        waitFor("Search query input holds \"$value\" and an enabled Add button shows") {
            input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text == value &&
                compose.onAllNodes(add, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        activate(compose.onNode(add, useUnmergedTree = true))
        try {
            waitFor("\"$value\" becomes a tag chip and the input clears") {
                input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty() &&
                    compose.onAllNodes(hasText(value, substring = false) and hasClickAction())
                        .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "Tag entry did not settle for $value; input=" +
                    input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text + "\n" +
                    compose.onNodeWithTag("Search composer", useUnmergedTree = true).printToString(),
                timeout,
            )
        }
    }

    /**
     * Drags Search results until [source]'s card at [index] is the grid's first visible item and has started to
     * scroll off the top, so the grid saves a positive pixel offset for it. The tagged grid wrapper receives
     * touches; its nested lazy grid owns scroll semantics.
     */
    protected fun scrollToFirstVisibleCard(source: SourceKey, index: Int, maxDrags: Int = 24) {
        val grid = compose.onNodeWithTag("Search results", useUnmergedTree = true)
        repeat(maxDrags) {
            val viewport = grid.fetchSemanticsNode().boundsInRoot
            // Unclipped, so it goes negative once the card's top has passed the viewport's.
            val targetTop = visibleCard(source, index, viewport)?.positionInRoot?.y?.minus(viewport.top)
            val previousVisible = index > 0 && visibleCard(source, index - 1, viewport) != null
            // The preceding card leaving is not enough: the target can still sit in the 6dp row gap at or
            // below the top, where it has no offset yet. Capped drags can stop anywhere, including there.
            if (targetTop != null && targetTop <= -1f && !previousVisible) {
                compose.onNodeWithTag(searchCardTestTag(posts.getValue(source)[index].id)).assertIsDisplayed()
                return
            }
            val overshoot = viewport.width * 0.08f
            val distance = if (targetTop == null) viewport.height * 0.45f else {
                (targetTop + overshoot).coerceIn(overshoot, viewport.height * 0.45f)
            }
            grid.performTouchInput {
                val start = Offset(centerX, height * 0.8f)
                swipeWithVelocity(start, start - Offset(0f, distance), endVelocity = 0f, durationMillis = 600L)
            }
            compose.waitForIdle()
        }
        throw AssertionError("Search card $index did not become the first visible fixture row after $maxDrags drags")
    }

    /** The card's node while any of it shows inside [viewport]; its `boundsInRoot` are clipped to the viewport. */
    private fun visibleCard(source: SourceKey, index: Int, viewport: Rect): SemanticsNode? {
        val tag = searchCardTestTag(posts.getValue(source)[index].id)
        return compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.takeIf { node ->
            val bounds = node.boundsInRoot
            bounds.height > 0f && bounds.bottom > viewport.top && bounds.top < viewport.bottom
        }
    }

    /**
     * Saves the screen as `journey-shots/<name>.png` in the app's external files, where `adb pull` reaches it, for a
     * look at a journey's key moment. Only for screens of fixture media.
     */
    protected fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shots = checkNotNull(instrumentation.targetContext.getExternalFilesDir("journey-shots"))
        check(UiDevice.getInstance(instrumentation).takeScreenshot(shots.resolve("$name.png"))) { "No screenshot for $name" }
    }

    protected fun waitFor(
        what: String,
        timeoutMillis: Long = 10_000,
        diagnostics: () -> String = ::semanticsTree,
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(conditionDescription = what, timeoutMillis = timeoutMillis, condition = condition)
        } catch (timeout: ComposeTimeoutException) {
            val state = runCatching(diagnostics).getOrElse { "Diagnostics failed: $it" }
            throw ComposeTimeoutException("${timeout.message}\n$state").apply { initCause(timeout) }
        }
    }

    private fun semanticsTree(): String = compose.onRoot(useUnmergedTree = true).printToString()

    private fun waitForNode(node: SemanticsNodeInteraction, what: String, condition: () -> Boolean) =
        waitFor("control is $what", diagnostics = {
            runCatching { node.printToString() }.getOrElse { it.message.orEmpty() } + "\n" + semanticsTree()
        }, condition = condition)

    protected fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    @After
    fun closeFixture() {
        if (::scenario.isInitialized) scenario.close()
        JourneyTestActivity.fixtureContainer = null
        if (::container.isInitialized) io { container.shutdown() }
        scope.cancel()
        if (::directory.isInitialized) directory.deleteRecursively()
    }
}
