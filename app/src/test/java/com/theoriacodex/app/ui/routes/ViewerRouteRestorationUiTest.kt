package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.media.MediaDurationAcquirer
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.ViewerMediaPrefetcher
import com.theoriacodex.app.viewer.ViewerPostResolver
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerViewModel
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ViewerRouteRestorationUiTest {
    @get:Rule val compose = createComposeRule()
    private val post = testPost(
        preview = ImageRef(null, "/nonexistent/viewer-exit.jpg", "image/jpeg"), full = null,
    )
    private val session = ViewerSession(listOf(post), ViewerLaunchContext("exit", 0, ViewerStreamSource.SEARCH, 0))

    @Test fun `dismiss retains outgoing media without restoration UI during suspended navigation`() {
        val dismiss = CompletableDeferred<Unit>()
        var dismissed = false
        withRoute(onDismiss = { dismissed = true; dismiss.await() }) {
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil { dismissed }
            assertExitingPresentation()
            compose.runOnIdle { dismiss.complete(Unit) }
            assertExitingPresentation()
        }
    }

    @Test fun `Search exit keeps presentation without restoration UI`() {
        var wentToSearch = false
        withRoute(onGoToSearch = { wentToSearch = true }) {
            compose.onNodeWithContentDescription("More actions").performClick()
            compose.onNodeWithText("Info").performClick()
            compose.onNodeWithContentDescription("Go to Search").performClick()
            compose.runOnIdle { assertEquals(true, wentToSearch) }
            assertExitingPresentation()
        }
    }

    @Test fun `shell-owned Creator exit clears owner without showing restoration UI`() {
        var owner: ViewerRouteOwnerHandle? = null
        withRoute(onOwner = { owner = it }) {
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(true, requireNotNull(owner).clearSession())
                assertNull(owner?.session?.value)
            }
            assertExitingPresentation()
        }
    }

    @Test fun `loading message appears only while a saved session is actually being reconstructed`() {
        val restoration = CompletableDeferred<ViewerSession?>()
        var restoreCalls = 0
        withRoute(coldOwner = coldOwner(), restore = { restoreCalls++; restoration.await() }) {
            compose.onNodeWithText("Restoring your place…").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(1, restoreCalls)
                restoration.complete(session)
            }
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.onNodeWithText("Restoring your place…").assertDoesNotExist()
        }
    }

    @Test fun `failed restoration stops loading and Retry shows progress for its new request`() {
        var restoration = CompletableDeferred<ViewerSession?>()
        withRoute(coldOwner = coldOwner(), restore = { restoration.await() }) {
            compose.onNodeWithText("Restoring your place…").assertIsDisplayed()
            compose.runOnIdle { restoration.complete(null) }
            compose.onNodeWithText("This viewing session could not be restored.").assertIsDisplayed()
            compose.onNodeWithText("Restoring your place…").assertDoesNotExist()
            compose.runOnIdle { restoration = CompletableDeferred() }
            compose.onNodeWithText("Retry").performClick()
            compose.onNodeWithText("Restoring your place…").assertIsDisplayed()
            compose.runOnIdle { restoration.complete(session) }
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.onNodeWithText("Restoring your place…").assertDoesNotExist()
        }
    }

    private fun coldOwner(): ViewerViewModel {
        val saved = SavedStateHandle()
        ViewerViewModel(saved).replaceSession(session)
        return ViewerViewModel(saved)
    }

    private fun assertExitingPresentation() {
        compose.onNodeWithText("Restoring your place…").assertDoesNotExist()
        compose.onNodeWithText("This viewing session could not be restored.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Media actions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    private fun withRoute(
        coldOwner: ViewerViewModel? = null,
        restore: suspend () -> ViewerSession? = { error("A live handoff or exit must not restore") },
        onDismiss: suspend () -> Unit = {},
        onOwner: (ViewerRouteOwnerHandle?) -> Unit = {},
        onGoToSearch: () -> Unit = {},
        verify: () -> Unit,
    ) {
        val retention = ViewerSessionRetentionViewModel()
        if (coldOwner == null) retention.retain(session)
        val durations = MediaDurationCoordinator(MediaDurationAcquirer { error("Static post must not acquire duration") })
        try {
            compose.setContent {
                val storeOwner = requireNotNull(LocalViewModelStoreOwner.current)
                remember(storeOwner) {
                    if (coldOwner != null) ViewModelProvider(storeOwner, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = coldOwner as T
                    })[ViewerViewModel::class.java]
                }
                MaterialTheme {
                    ViewerRoute(
                        dependencies = ViewerRouteDependencies(
                            retention, ViewerPostResolver { _, _ -> post },
                            ViewerMediaPrefetcher { _, _ -> ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED) },
                            durations, restoreSession = { restore() },
                        ),
                        renderConfig = ViewerRouteRenderConfig(creatorBrowsingSources = emptySet()),
                        liveSourceState = ViewerRouteLiveSourceState(),
                        effectCallbacks = ViewerRouteEffectCallbacks(
                            onSavePost = { _, _ -> }, onSharePost = {}, onDownloadMedia = {}, onToggleLike = {},
                            onOpenCreatorProfile = {}, onApplyTag = { _, _, _ -> },
                            onRecoverMedia = { _, _, _ -> null }, onLoadMore = {},
                            onDismiss = onDismiss, onRestorationUnavailable = {},
                        ),
                        screenCallbacks = ViewerRouteScreenCallbacks(
                            onOwnerChanged = onOwner, onOpenInBrowser = {}, onRemoveIncludeTerm = { _, _ -> },
                            onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = onGoToSearch,
                        ),
                    )
                }
            }
            verify()
        } finally { durations.close() }
    }
}
