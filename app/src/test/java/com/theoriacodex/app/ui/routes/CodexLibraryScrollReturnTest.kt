package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.codex.CodexListScreen
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.domain.model.Codex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CodexLibraryScrollReturnTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `library retains scrolled collection across detail return and saved state restoration`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val graph = runBlocking(Dispatchers.IO) {
            JourneyAppContainer(ApplicationProvider.getApplicationContext(), temporary.newFolder(), emptyMap(), scope)
                .also { graph ->
                    graph.awaitReady()
                    repeat(40) { graph.content.ensureCodex("collection-$it", "Collection $it") }
                }
        }
        // Hold each subscription until the test observes the destination without a grid.
        val pendingLoads = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
        val repository = object : CodexRepository by graph.content {
            override fun observeCodices(): Flow<List<Codex>> = flow {
                val ready = CompletableDeferred<Unit>()
                pendingLoads.send(ready)
                ready.await()
                emitAll(graph.content.observeCodices())
            }
        }
        val data = graph.data.copy(codexRepository = repository)
        val show = mutableStateOf(true)
        val grid = hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        fun releaseLibraryData() {
            var pending: CompletableDeferred<Unit>? = null
            compose.waitUntil(conditionDescription = "library subscribes to delayed collection data", timeoutMillis = 10_000) {
                pending = pendingLoads.tryReceive().getOrNull()
                pending != null
            }
            // An empty placeholder grid would clamp the saved anchor before real data arrives.
            compose.onNode(grid).assertDoesNotExist()
            compose.runOnIdle { requireNotNull(pending).complete(Unit) }
        }
        try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                MaterialTheme {
                    if (show.value) {
                        val navigation = rememberNavController()
                        NavHost(navigation, startDestination = "library") {
                            composable("library") {
                                CodexDestinationStateBoundary(data, graph.sources, 0) { state ->
                                    CodexListScreen(
                                        codices = state.visibleCodices,
                                        itemCounts = state.itemCounts,
                                        codexCoverCandidates = state.coverCandidates,
                                        observeActionOptions = state.observeActionOptions,
                                        likesCodexId = "system_likes_codex",
                                        onOpenCodex = { navigation.navigate("detail") },
                                        onImportCodex = {},
                                        onDownloadCodex = {},
                                        onShareCodex = {},
                                        onSearchFromCodex = { _, _, _ -> },
                                        onCommitReorder = {},
                                        onCreateCodex = {},
                                        onRenameCodex = { _, _ -> },
                                        onSetAutomaticTag = { _, _, _ -> },
                                        onDeleteCodex = {},
                                        onCleanCodex = { _, _ -> },
                                    )
                                }
                            }
                            composable("detail") {
                                TextButton(onClick = { navigation.popBackStack() }) { Text("Return to library") }
                            }
                        }
                    }
                }
            }
            releaseLibraryData()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Collection", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(grid).performScrollToIndex(24)
            val collection = compose.onNodeWithText("Collection 24")
            collection.assertIsDisplayed()
            val top = collection.fetchSemanticsNode().boundsInRoot.top
            repeat(2) { iteration ->
                collection.performClick()
                compose.onNodeWithText("Return to library").assertIsDisplayed()
                if (iteration == 1) restoration.emulateSavedInstanceStateRestore()
                compose.onNodeWithText("Return to library").performClick()
                releaseLibraryData()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Collection 24").fetchSemanticsNodes().isNotEmpty()
                }
                collection.assertIsDisplayed()
                assertEquals(top, collection.fetchSemanticsNode().boundsInRoot.top, 1f)
            }
        } catch (error: Throwable) {
            println(compose.onRoot().printToString())
            throw error
        } finally {
            compose.runOnIdle { show.value = false }
            runBlocking(Dispatchers.IO) { graph.shutdown() }
            scope.cancel()
            pendingLoads.close()
        }
    }
}
