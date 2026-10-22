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
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
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
@Config(application = Application::class)
class ViewerSaveOriginTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `fullscreen like retains its originating feed action after handoff consumption`() {
        val post = testPost(SourceKey.PIXIV, "like-origin").copy(
            preview = ImageRef(null, "/nonexistent/preview.jpg", "image/jpeg"), full = null, media = emptyList(),
        )
        val retention = ViewerSessionRetentionViewModel()
        val likedPosts = mutableListOf<com.theoriacodex.domain.model.Post>()
        retention.retain(ViewerSession(
            listOf(post), ViewerLaunchContext("related:PIXIV:seed", 0, ViewerStreamSource.RELATED, 0),
            onToggleLike = { likedPosts += it },
        ))
        val durations = MediaDurationCoordinator(MediaDurationAcquirer { error("Static post must not acquire duration") })
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
                            onSavePost = { _, _ -> }, onSharePost = {}, onDownloadMedia = {},
                            onToggleLike = { error("Originating feed action must handle this Like") },
                            onOpenCreatorProfile = {}, onApplyTag = { _, _, _ -> },
                            onRecoverMedia = { _, _, _ -> null }, onLoadMore = {},
                            onDismiss = {}, onRestorationUnavailable = {},
                        ),
                        screenCallbacks = ViewerRouteScreenCallbacks(
                            onOpenInBrowser = {}, onRemoveIncludeTerm = { _, _ -> },
                            onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = {},
                        ),
                    )
                }
            }
            compose.runOnIdle { assertNull(retention.session.value) }
            compose.onNodeWithContentDescription("Like post").performClick()
            compose.runOnIdle { assertEquals(listOf(post.id), likedPosts.map { it.id }) }
        } finally { durations.close() }
    }

    @Test
    fun `save from Viewer reports For You after the navigation handoff is consumed`() {
        assertViewerSaveOrigin(ViewerLaunchContext("for_you:original", 0, ViewerStreamSource.FOR_YOU, 0))
    }

    @Test
    fun `Search Viewer retains its captured grouped query for saving after handoff consumption`() {
        val query = emptySearchQuery().withIncludeTermGroups(listOf(
            SearchTermGroup.single(SearchTerm("yellow")),
            SearchTermGroup(listOf(SearchTerm("cat"), SearchTerm("dog"))),
        ))
        assertViewerSaveOrigin(ViewerLaunchContext("search:original", 0, ViewerStreamSource.SEARCH, 0), query)
    }

    private fun assertViewerSaveOrigin(context: ViewerLaunchContext, query: Query? = null) {
        val post = testPost(SourceKey.PIXIV, "save-origin").copy(
            preview = ImageRef(null, "/nonexistent/preview.jpg", "image/jpeg"), full = null, media = emptyList(),
        )
        val retention = ViewerSessionRetentionViewModel()
        retention.retain(ViewerSession(listOf(post), context, saveQuery = query))
        val durations = MediaDurationCoordinator(MediaDurationAcquirer { error("Static post must not acquire duration") })
        var savedContext: ViewerLaunchContext? = null
        var savedQuery: Query? = null
        var owner: ViewerRouteOwnerHandle? = null
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
                            onSavePost = { _, origin ->
                                savedContext = origin
                                savedQuery = owner?.session?.value?.saveQuery
                            }, onSharePost = {},
                            onDownloadMedia = {}, onToggleLike = {}, onOpenCreatorProfile = {},
                            onApplyTag = { _, _, _ -> }, onRecoverMedia = { _, _, _ -> null },
                            onLoadMore = {}, onDismiss = {}, onRestorationUnavailable = {},
                        ),
                        screenCallbacks = ViewerRouteScreenCallbacks(
                            onOwnerChanged = { owner = it },
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
            compose.runOnIdle {
                assertEquals(context, savedContext)
                assertEquals(query, savedQuery)
            }
        } finally {
            durations.close()
        }
    }
}
