package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.media.MediaDurationAcquirer
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.ViewerMediaPrefetcher
import com.theoriacodex.app.viewer.ViewerPostResolver
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ViewerSaveOriginTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `save from Viewer reports For You after the navigation handoff is consumed`() {
        val post = testPost(SourceKey.PIXIV, "save-origin").copy(
            preview = ImageRef(null, "/nonexistent/preview.jpg", "image/jpeg"), full = null, media = emptyList(),
        )
        val retention = ViewerSessionRetentionViewModel()
        val context = ViewerLaunchContext("for_you:original", 0, ViewerStreamSource.FOR_YOU, 0)
        retention.retain(ViewerSession(listOf(post), context))
        val durations = MediaDurationCoordinator(MediaDurationAcquirer { error("Static post must not acquire duration") })
        var savedContext: ViewerLaunchContext? = null
        try {
            compose.setContent {
                MaterialTheme {
                    ViewerRoute(
                        dependencies = ViewerRouteDependencies(
                            retention, ViewerPostResolver { _, _ -> post },
                            ViewerMediaPrefetcher { _, _ -> ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED) },
                            durations, restoreSession = { null },
                        ),
                        renderConfig = ViewerRouteRenderConfig(creatorBrowsingSources = emptySet()),
                        liveSourceState = ViewerRouteLiveSourceState(),
                        effectCallbacks = ViewerRouteEffectCallbacks(
                            onSavePost = { _, origin -> savedContext = origin }, onSharePost = {},
                            onDownloadMedia = {}, onToggleLike = {}, onOpenCreatorProfile = {},
                            onApplyTag = { _, _, _ -> }, onRecoverMedia = { _, _, _ -> null },
                            onLoadMore = {}, onDismiss = {}, onRestorationUnavailable = {},
                        ),
                        screenCallbacks = ViewerRouteScreenCallbacks(
                            onOpenInBrowser = {}, onRemoveIncludeTerm = { _, _ -> },
                            onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = {},
                        ),
                    )
                }
            }
            compose.runOnIdle { assertNull(retention.session.value) }
            compose.onNodeWithContentDescription("More actions").performClick()
            compose.onNodeWithText("Info").performClick()
            compose.onNodeWithContentDescription("Save to Codex").performClick()
            compose.runOnIdle { assertEquals(context, savedContext) }
        } finally {
            durations.close()
        }
    }
}
