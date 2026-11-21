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
import com.theoriacodex.app.viewer.ViewerScreenCallbacks
import com.theoriacodex.app.viewer.ViewerScreenConfig
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerViewModel
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import com.theoriacodex.app.viewer.testViewerOwner
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
@Config(application = Application::class)
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

    @Test fun `Back dismisses while a media retry still waits on the provider`() {
        val owner = testViewerOwner(SavedStateHandle())
        val recovery = CompletableDeferred<com.theoriacodex.domain.model.Post?>()
        var dismissed = false
        withRoute(liveOwner = owner, onRecoverMedia = { recovery.await() }, onDismiss = { dismissed = true }) {
            compose.runOnIdle {
                val identity = requireNotNull(owner.state.value.session)
                val key = requireNotNull(owner.state.value.currentMedia).key
                owner.onAction(ViewerAction.MediaFailed(identity, ViewerMediaError.Recoverable(key, "Network")))
                owner.onAction(ViewerAction.RetryMedia)
            }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil { dismissed }
            compose.runOnIdle { recovery.complete(null) }
        }
    }

    @Test fun `Search exit keeps presentation without restoration UI`() {
        var wentToSearch = false
        withRoute(onGoToSearch = { wentToSearch = true }) {
            compose.onNodeWithContentDescription("More actions").performClick()
            compose.onNodeWithText("Info").performClick()
            compose.onNodeWithContentDescription("More post actions").performClick()
            compose.onNodeWithText("Remove from Codex").assertDoesNotExist()
            compose.onNodeWithText("Go to Search").performClick()
            compose.runOnIdle { assertEquals(true, wentToSearch) }
            assertExitingPresentation()
        }
    }

    @Test fun `Info removes the post from the stored Codex the Viewer was opened from`() {
        var removed: Pair<String, String>? = null
        withRoute(
            launchSession = session.copy(context = session.context.copy(queryHash = "codex:collection", streamSource = ViewerStreamSource.CODEX)),
            onRemoveFromCodex = { codexId, removedPost -> removed = codexId to removedPost.id.sourcePostId },
        ) {
            compose.onNodeWithContentDescription("More actions").performClick()
            compose.onNodeWithText("Info").performClick()
            compose.onNodeWithContentDescription("More post actions").performClick()
            compose.onNodeWithText("Remove from Codex").performClick()
            compose.runOnIdle { assertEquals("collection" to post.id.sourcePostId, removed) }
        }
        fun codexContext(id: String) = ViewerLaunchContext("codex:$id", 0, ViewerStreamSource.CODEX, 0)
        listOf("system:saved", "system:followed", "system:saved-searches").forEach { id -> assertNull(codexContext(id).removableCodexId()) }
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

    @Test fun `Codex viewing publishes canonical attribution and clears it on exit despite retained pages`() {
        var owner: ViewerRouteOwnerHandle? = null
        var target: com.theoriacodex.data.repository.CodexUsageTarget? = null
        withRoute(
            launchSession = session.copy(context = session.context.copy(
                queryHash = "codex:collection", streamSource = ViewerStreamSource.CODEX,
            )),
            onUsage = { target = it }, onOwner = { owner = it },
        ) {
            compose.runOnIdle {
                assertEquals("collection", target?.codexId)
                assertEquals("${post.id.source.id}:${post.id.sourcePostId}", target?.postKey)
                assertEquals(false, target?.video)
                requireNotNull(owner).clearSession()
            }
            compose.runOnIdle { assertNull(target) }
        }
    }

    private fun coldOwner(): ViewerViewModel {
        val saved = SavedStateHandle()
        testViewerOwner(saved).replaceSession(session)
        return testViewerOwner(saved)
    }

    private fun assertExitingPresentation() {
        compose.onNodeWithText("Restoring your place…").assertDoesNotExist()
        compose.onNodeWithText("This viewing session could not be restored.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Media actions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    private fun withRoute(
        launchSession: ViewerSession = session,
        onUsage: (com.theoriacodex.data.repository.CodexUsageTarget?) -> Unit = {},
        coldOwner: ViewerViewModel? = null,
        liveOwner: ViewerViewModel? = null,
        onRecoverMedia: suspend () -> com.theoriacodex.domain.model.Post? = { null },
        restore: suspend () -> ViewerSession? = { error("A live handoff or exit must not restore") },
        onDismiss: suspend () -> Unit = {},
        onOwner: (ViewerRouteOwnerHandle?) -> Unit = {},
        onGoToSearch: () -> Unit = {},
        onRemoveFromCodex: (String, com.theoriacodex.domain.model.Post) -> Unit = { _, _ -> },
        verify: () -> Unit,
    ) {
        val retention = ViewerSessionRetentionViewModel()
        if (coldOwner == null) retention.retain(launchSession)
        val durations = MediaDurationCoordinator(MediaDurationAcquirer { error("Static post must not acquire duration") })
        try {
            compose.setContent {
                val storeOwner = requireNotNull(LocalViewModelStoreOwner.current)
                val injectedOwner = coldOwner ?: liveOwner
                if (injectedOwner != null) remember(storeOwner) {
                    ViewModelProvider(storeOwner, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = injectedOwner as T
                    })[ViewerViewModel::class.java]
                }
                MaterialTheme {
                    ViewerRoute(
                        dependencies = ViewerRouteDependencies(
                            retention, ViewerPostResolver { _, _ -> post },
                            ViewerMediaPrefetcher { _, _, _ -> ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED) },
                            durations, restoreSession = { restore() }, onCodexUsageTargetChanged = onUsage,
                        ),
                        config = ViewerScreenConfig(),
                        liveSourceState = ViewerRouteLiveSourceState(),
                        effectCallbacks = ViewerRouteEffectCallbacks(
                            onSavePost = { _, _ -> }, onDownloadMedia = {}, onToggleLike = {},
                            onOpenCreatorProfile = {}, onApplyTag = { _, _, _ -> },
                            onRecoverMedia = { _, _, _ -> onRecoverMedia() }, onLoadMore = {},
                            onDismiss = onDismiss, onRestorationUnavailable = {},
                        ),
                        screenCallbacks = ViewerScreenCallbacks(
                            onRemoveIncludeTerm = { _, _ -> },
                            onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = onGoToSearch,
                            onRemoveFromCodex = onRemoveFromCodex,
                        ),
                        onOwnerChanged = onOwner,
                    )
                }
            }
            verify()
        } finally { durations.close() }
    }
}
