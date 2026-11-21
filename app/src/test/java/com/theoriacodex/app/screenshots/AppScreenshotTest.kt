package com.theoriacodex.app.screenshots

import com.theoriacodex.app.library.LIBRARY_SETTINGS_TAG
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.library.librarySectionTag
import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import com.github.takahirom.roborazzi.captureRoboImage
import com.theoriacodex.app.appshell.AppShellViewModel
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.TheoriaAppContent
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshots of the app's main screens, rendered from the real app graph over fixture posts whose
 * images are solid colors. `scripts/screenshots.sh` captures them; CI verifies them against Linux references.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val showApp = mutableStateOf(true)
    private val posts = (1..8).map { index ->
        val image = ImageRef("https://example.test/screenshot-$index.jpg", null, "image/jpeg")
        testPost(SourceKey.PIXIV, "screenshot-$index").copy(
            preview = image, full = image, media = listOf(image),
            width = if (index % 3 == 0) 160 else 120, height = if (index % 2 == 0) 160 else 120,
            title = "Fixture post $index", authorName = "Fixture artist", canonicalTags = listOf("landscape"),
        )
    }
    private lateinit var graph: JourneyAppContainer

    @Before fun launch() {
        installSolidColorImages()
        graph = runBlocking(Dispatchers.IO) {
            JourneyAppContainer(ApplicationProvider.getApplicationContext(), temporary.newFolder(),
                mapOf(SourceKey.PIXIV to posts), scope).also {
                it.awaitReady()
                it.content.ensureCodex(CODEX_ID, "Fixture Codex")
                // Distinct save times keep the newest-saved order stable; items added together can share a millisecond.
                it.content.restoreItems(posts.mapIndexed { index, post -> CodexItem(CODEX_ID, post.id, index.toLong()) }, posts)
                it.data.recentsRepository.recordSearch(
                    Query(QueryMode.Source(SourceKey.PIXIV), listOf("landscape"), emptyList(), SortMode.NEWEST, null, null),
                    SEARCH_ID, RecentSearchKind.SOURCE, listOf(SourceKey.PIXIV), emptyMap(),
                )
            }
        }
        compose.setContent {
            if (showApp.value) TheoriaAppContent(graph, viewModel<ViewerSessionRetentionViewModel>(), viewModel<AppShellViewModel>())
        }
        waitFor { compose.onAllNodesWithContentDescription("Library").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun close() {
        compose.runOnIdle { showApp.value = false }
        compose.waitForIdle()
        runBlocking(Dispatchers.IO) { graph.shutdown() }
        scope.cancel()
        Coil.reset()
    }

    /** Also the app shell: search bar and navigation. The empty Search screen is skipped for its bundled artwork. */
    @Test fun `search results`() {
        openSearchResults()
        capture("search_results")
    }

    @Test fun `codex detail`() {
        openLibrary(LibrarySection.Codices)
        waitFor { compose.onAllNodesWithText("Fixture Codex").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Fixture Codex").performClick()
        waitFor { compose.onAllNodesWithTag(searchCardTestTag(posts[0].id)).fetchSemanticsNodes().isNotEmpty() }
        capture("codex_detail")
    }

    @Test fun `viewer`() {
        openSearchResults()
        compose.onNodeWithTag(searchCardTestTag(posts[0].id)).performClick()
        waitFor { compose.onNodeWithContentDescription("More actions").isDisplayed() }
        capture("viewer")
    }

    @Test fun `settings`() {
        compose.onNodeWithContentDescription("Library").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LIBRARY_SETTINGS_TAG).performClick()
        // Stats load after the screen; the Codex's posts count as saved.
        waitFor { compose.onAllNodesWithText("${posts.size} saved", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("settings")
    }

    private fun openLibrary(section: LibrarySection) {
        compose.onNodeWithContentDescription("Library").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(librarySectionTag(section)).performClick()
    }

    private fun openSearchResults() {
        openLibrary(LibrarySection.History)
        waitFor { compose.onNodeWithContentDescription("Filter Recents by section").isDisplayed() }
        compose.onNodeWithContentDescription("Filter Recents by section").performClick()
        compose.onNodeWithText("Searches").performClick()
        waitFor { compose.onAllNodesWithTag("Recent search:$SEARCH_ID").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("Recent search:$SEARCH_ID").performClick()
        waitFor { compose.onAllNodesWithTag(searchCardTestTag(posts[0].id)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15_000, condition)

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$name.png")
    }

    private companion object {
        const val CODEX_ID = "fixture-codex"
        const val SEARCH_ID = "fixture-search"
    }
}

/** Every image request succeeds at once with a solid color derived from its URL, so screens are deterministic. */
private fun installSolidColorImages() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    Coil.setImageLoader(
        ImageLoader.Builder(context)
            .dispatcher(Dispatchers.Unconfined)
            .components {
                add(
                    Fetcher.Factory<Any> { data, _, _ ->
                        Fetcher {
                            val hue = (data.toString().hashCode() and Int.MAX_VALUE) % 360
                            val color = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.8f))
                            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                            DrawableResult(BitmapDrawable(context.resources, bitmap), false, DataSource.MEMORY)
                        }
                    },
                )
            }
            .build(),
    )
}
