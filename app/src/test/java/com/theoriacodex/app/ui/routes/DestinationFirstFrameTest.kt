package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.domain.model.Codex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Screens return to composition constantly, so each must draw from what it already knows. The gates
 * block repeat subscriptions forever, which makes needing to re-query fail rather than merely race.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DestinationFirstFrameTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val graph by lazy {
        runBlocking(Dispatchers.IO) {
            JourneyAppContainer(ApplicationProvider.getApplicationContext(), temporary.newFolder(), emptyMap(), scope).also { container ->
                container.awaitReady()
                container.content.ensureCodex("kept", "Kept Codex")
            }
        }
    }

    private val show = mutableStateOf(true)

    @After fun cleanup() {
        compose.runOnIdle { show.value = false }
        compose.waitForIdle()
        runBlocking(Dispatchers.IO) { graph.shutdown() }
        scope.cancel()
    }

    private fun onlyFirstSettingsSubscriptionAnswers(): DataDependencies {
        val subscriptions = AtomicInteger()
        val settings = object : SettingsRepository by graph.data.settingsRepository {
            override fun observeSettings(): Flow<AppSettings> = flow {
                if (subscriptions.incrementAndGet() > 1) awaitCancellation()
                emitAll(graph.data.settingsRepository.observeSettings())
            }
        }
        return graph.data.copy(settingsRepository = settings)
    }

    private fun assertAppearsAtOnce(text: String, navigate: () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle(navigate)
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithText(text).assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun `a new destination has shared browsing state on its first frames`() {
        val data = onlyFirstSettingsSubscriptionAnswers()
        lateinit var navigation: NavHostController
        compose.setContent {
            navigation = rememberNavController()
            if (show.value) NavHost(navigation, startDestination = "home") {
                composable("home") { BrowsingDestinationStateBoundary(data, graph.sources) { Text("home ready") } }
                composable("viewer") { BrowsingDestinationStateBoundary(data, graph.sources) { Text("viewer ready") } }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTextReady("home ready")).fetchSemanticsNodes().isNotEmpty() }
        assertAppearsAtOnce("viewer ready") { navigation.navigate("viewer") }
    }

    @Test fun `a Codex is loading rather than missing until it answers, and is kept after returning from the Viewer`() {
        val release = CompletableDeferred<Unit>()
        val subscriptions = AtomicInteger()
        val codices = object : CodexRepository by graph.content {
            override fun observeCodex(codexId: String): Flow<Codex?> = flow {
                release.await()
                if (subscriptions.incrementAndGet() > 1) awaitCancellation()
                emitAll(graph.content.observeCodex(codexId))
            }
        }
        val data = graph.data.copy(codexRepository = codices)
        lateinit var navigation: NavHostController
        compose.setContent {
            navigation = rememberNavController()
            if (show.value) NavHost(navigation, startDestination = "codex") {
                composable("codex") {
                    CodexDetailDestinationStateBoundary("kept", CodexSortMode.NEWEST_SAVED, data, graph.sources, FeedFabRestoreState()) { state ->
                        Text(if (state.loading) "still loading" else state.codex?.name ?: "codex missing")
                    }
                }
                composable("viewer") { Text("viewer") }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTextReady("still loading")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("codex missing").assertDoesNotExist()
        release.complete(Unit)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTextReady("Kept Codex")).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { navigation.navigate("viewer") }
        compose.waitForIdle()
        assertAppearsAtOnce("Kept Codex") { navigation.popBackStack() }
    }

    private fun hasTextReady(text: String) = androidx.compose.ui.test.hasText(text)
}
