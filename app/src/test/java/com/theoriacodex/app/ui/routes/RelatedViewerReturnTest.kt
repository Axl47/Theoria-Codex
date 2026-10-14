package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.appshell.AppShellViewModel
import com.theoriacodex.app.TheoriaApplication
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.TheoriaAppContent
import com.theoriacodex.data.repository.RecentSearchKind
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RelatedViewerReturnTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `Search related shelf survives repeated fullscreen return`() = verifyReturn(RecentSearchKind.SOURCE)
    @Test fun `For You related shelf survives repeated fullscreen return`() = verifyReturn(RecentSearchKind.FYP)
    @Test
    @Config(application = TheoriaApplication::class, shadows = [ViewerReturnApplicationShadow::class])
    fun `Gelbooru video shelf survives Search fullscreen return`() = verifyReturn(RecentSearchKind.SOURCE, SourceKey.GELBOORU, true)
    @Test
    @Config(application = TheoriaApplication::class, shadows = [ViewerReturnApplicationShadow::class])
    fun `Gelbooru video shelf survives For You fullscreen return`() = verifyReturn(RecentSearchKind.FYP, SourceKey.GELBOORU, true)

    private fun verifyReturn(kind: RecentSearchKind, source: SourceKey = SourceKey.PIXIV, video: Boolean = false) {
        val videoPath = if (video) {
            val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile }
            File(root, "app/src/benchmarkRelease/res/raw/benchmark_loop.mp4").absolutePath
        } else null
        val posts = (0..35).map { index ->
            val media = ImageRef(null, "/nonexistent/related-$index.jpg", "image/jpeg")
            val full = videoPath?.let { ImageRef(null, it, "video/mp4") } ?: media
            testPost(source, "return-$index").copy(
                preview = media, full = full, media = listOf(full), width = 160, height = 120,
                canonicalTags = listOf(if (index >= 6) "seed" else "other"),
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val showApp = mutableStateOf(true)
        var viewModelOwner: ViewModelStoreOwner? = null
        val graph = runBlocking(Dispatchers.IO) {
            JourneyAppContainer(ApplicationProvider.getApplicationContext(), temporary.newFolder(),
                mapOf(source to posts), scope).also {
                it.awaitReady()
                it.data.recentsRepository.recordSearch(
                    Query(if (kind == RecentSearchKind.FYP) QueryMode.Unified else QueryMode.Source(source),
                        listOf("seed"), emptyList(), SortMode.NEWEST, null, null),
                    "return-query", kind, listOf(source),
                    if (kind == RecentSearchKind.FYP) mapOf(source to listOf("seed")) else emptyMap(),
                )
            }
        }
        try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val owner = LocalViewModelStoreOwner.current
                SideEffect { viewModelOwner = owner }
                if (showApp.value) TheoriaAppContent(graph, viewModel<ViewerSessionRetentionViewModel>(), viewModel<AppShellViewModel>())
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Recents").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Recents").performClick()
            compose.onNodeWithText(if (kind == RecentSearchKind.FYP) "FYP" else "Searches").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("Recent search:return-query").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("Recent search:return-query").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(searchCardTestTag(posts[6].id)).fetchSemanticsNodes().isNotEmpty() }
            val verticalGrid = hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            compose.onNode(verticalGrid).performScrollToIndex(10)
            val seedTag = searchCardTestTag(posts[16].id)
            compose.onNode(hasContentDescription("Like post") and hasAnyAncestor(hasTestTag(seedTag)), useUnmergedTree = true)
                .performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("More like this").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(verticalGrid, useUnmergedTree = true)
                .performScrollToNode(hasTestTag(searchCardTestTag(posts[0].id)))
            val relatedCard = compose.onNodeWithTag(searchCardTestTag(posts[0].id))
            val shelfTop = relatedCard.fetchSemanticsNode().boundsInRoot.top
            val roots = graph.registry.requests.count { it.pageToken == null }
            repeat(3) { iteration ->
                relatedCard.assertIsDisplayed().performClick()
                compose.waitUntil(10_000) { compose.onNodeWithContentDescription("Back").isDisplayed() }
                if (iteration == 1) {
                    restoration.emulateSavedInstanceStateRestore()
                    compose.waitUntil(10_000) { compose.onNodeWithContentDescription("Back").isDisplayed() }
                }
                compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("More like this").fetchSemanticsNodes().isNotEmpty() }
                relatedCard.assertIsDisplayed()
                assertEquals(shelfTop, relatedCard.fetchSemanticsNode().boundsInRoot.top, 1f)
                assertEquals(roots, graph.registry.requests.count { it.pageToken == null })
            }
        } catch (error: Throwable) {
            println(compose.onRoot().printToString())
            throw error
        } finally {
            compose.runOnIdle {
                showApp.value = false
                viewModelOwner?.viewModelStore?.clear()
            }
            compose.waitForIdle()
            runBlocking(Dispatchers.IO) { graph.shutdown() }
            scope.cancel()
        }
    }
}

/** Keep the real application-owned video runtime while the test supplies its isolated fixture graph. */
@Implements(TheoriaApplication::class)
class ViewerReturnApplicationShadow : org.robolectric.shadows.ShadowApplication() {
    @Implementation fun onCreate() = Unit
}
