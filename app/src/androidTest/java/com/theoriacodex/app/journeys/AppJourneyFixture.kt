package com.theoriacodex.app.journeys

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.fixtures.JourneyTestActivity
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
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
        val image = directory.resolve("landscape.png")
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        posts = listOf(SourceKey.PIXIV, SourceKey.GELBOORU).associateWith { source ->
            (0 until 60).map { index ->
                val media = ImageRef(null, image.absolutePath, "image/png")
                Post(
                    id = PostId(source, "journey-$index"), preview = media, full = media,
                    pageUrl = null, width = 320, height = 320,
                    canonicalTags = listOf("landscape", if (index % 2 == 0) "blue" else "green", "multi",
                        if (source == SourceKey.PIXIV) "pixiv_seed" else "gelbooru_seed"),
                    rawTags = emptyList(), authorName = "Journey artist", createdAtEpochMs = 1_000L + index,
                    title = "${source.name} landscape $index",
                    creatorProfile = CreatorProfile(source, "Journey artist", "journey-artist", uploadsQuery = "journey-artist"),
                )
            }
        }
        openGraph()
    }

    private fun openGraph() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        container = io {
            JourneyAppContainer(context, directory, posts, scope, pageSize = 20).also { it.awaitReady() }
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
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun tab(label: String) {
        activate(compose.onNodeWithContentDescription(label))
        compose.waitForIdle()
    }

    /** Exercise the visible control's action without racing its animated touch coordinates. */
    protected fun activate(node: SemanticsNodeInteraction) {
        compose.waitUntil(10_000) { node.isDisplayed() }
        node.performSemanticsAction(SemanticsActions.OnClick)
    }

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
