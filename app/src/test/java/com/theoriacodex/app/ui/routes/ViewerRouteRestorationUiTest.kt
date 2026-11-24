package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.compose.material3.MaterialTheme
import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.theoriacodex.app.viewer.LocalViewerReturnTransition
import com.theoriacodex.app.viewer.ViewerReturnTransition
import org.robolectric.Shadows.shadowOf
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
import com.theoriacodex.app.testing.waitUntilSettled
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

    @Test fun `dismiss retains outgoing media without restoration UI until navigation removes the Viewer`() {
        var dismissed = false
        withRoute(onDismiss = { dismissed = true }) {
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntilSettled { dismissed }
            assertExitingPresentation()
        }
    }

    @Test fun `two quick Backs close the Viewer, then the screen behind once the Viewer leaves`() {
        val transition = ViewerReturnTransition()
        val shown = mutableStateOf(true)
        var dispatcher: OnBackPressedDispatcher? = null
        var backsBehind = 0
        withRoute(wrap = { route ->
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            BackHandler { backsBehind++ }
            CompositionLocalProvider(LocalViewerReturnTransition provides transition) { if (shown.value) route() }
        }) {
            compose.waitForIdle()
            compose.runOnUiThread { dispatcher!!.onBackPressed() }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnUiThread { dispatcher!!.onBackPressed() }
            compose.waitForIdle()
            assertEquals(0, backsBehind)
            assertEquals(post.id, transition.request?.postId)

            shown.value = false
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, backsBehind)
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
            compose.waitUntilSettled { dismissed }
            compose.runOnIdle { recovery.complete(null) }
        }
    }

    @Test fun `media recovery begun before the lookup finished cannot replace its resolved post`() {
        val owner = testViewerOwner(SavedStateHandle())
        val recovery = CompletableDeferred<com.theoriacodex.domain.model.Post?>()
        var recoveryRequested = false
        val pages = listOf(1, 2).map { ImageRef(null, "/nonexistent/page-$it.jpg", "image/jpeg") }
        val resolved = post.copy(full = pages.first(), media = pages, mediaCount = pages.size)
        withRoute(liveOwner = owner, onRecoverMedia = { recoveryRequested = true; recovery.await() }) {
            compose.runOnIdle {
                val identity = requireNotNull(owner.state.value.session)
                val key = requireNotNull(owner.state.value.currentMedia).key
                owner.onAction(ViewerAction.MediaFailed(identity, ViewerMediaError.Recoverable(key, "Network")))
                owner.onAction(ViewerAction.RetryMedia)
            }
            compose.waitUntilSettled { recoveryRequested }
            compose.runOnIdle {
                owner.applyResolvedPost(resolved)
                val refreshed = ImageRef("https://example.com/refreshed.jpg", null, "image/jpeg")
                recovery.complete(post.copy(preview = refreshed))
            }
            compose.waitForIdle()
            assertEquals(pages, owner.state.value.pages.single().post.media)
        }
    }

    @Test fun `Search exit keeps presentation without restoration UI`() {
        var wentToSearch = false
        withRoute(onGoToSearch = { wentToSearch = true }) {
            compose.onNodeWithContentDescription("Info", substring = true).performClick()
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
            compose.onNodeWithContentDescription("Info", substring = true).performClick()
            compose.onNodeWithContentDescription("More post actions").performClick()
            compose.onNodeWithText("Remove from Codex").performClick()
            compose.runOnIdle { assertEquals("collection" to post.id.sourcePostId, removed) }
        }
        fun codexContext(id: String) = ViewerLaunchContext("codex:$id", 0, ViewerStreamSource.CODEX, 0)
        listOf("system:saved", "system:followed", "system:saved-searches").forEach { id -> assertNull(codexContext(id).removableCodexId()) }
    }

    @Test fun `shell-owned Creator exit clears owner without showing restoration UI`() {
        var owner: ViewerViewModel? = null
        withRoute(onOwner = { owner = it }) {
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.runOnIdle {
                requireNotNull(owner).clearSession(retainPresentation = true)
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
        var owner: ViewerViewModel? = null
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
                requireNotNull(owner).clearSession(retainPresentation = true)
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
        onDismiss: () -> Unit = {},
        onOwner: (ViewerViewModel?) -> Unit = {},
        onGoToSearch: () -> Unit = {},
        onRemoveFromCodex: (String, com.theoriacodex.domain.model.Post) -> Unit = { _, _ -> },
        wrap: @Composable (route: @Composable () -> Unit) -> Unit = { route -> route() },
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
                    wrap {
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
                            onRecoverMedia = { _, _, _, _ -> onRecoverMedia() }, onLoadMore = {},
                            onDismiss = onDismiss, onRestorationUnavailable = {},
                        ),
                        screenCallbacks = ViewerScreenCallbacks(
                            onGoToSearch = onGoToSearch,
                            onRemoveFromCodex = onRemoveFromCodex,
                        ),
                        onOwnerChanged = onOwner,
                    )
                    }
                }
            }
            verify()
        } finally { durations.close() }
    }
}
