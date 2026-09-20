package com.theoriacodex.app.journeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCollectionJourneyTest : AppJourneyFixture() {
    @Test
    fun collectionDownloadSurvivesDisposableClearAndColdGraphThenViewerSwipesWithoutProviderResolution() {
        val sourcePosts = posts.getValue(SourceKey.PIXIV).take(2)
        val snapshots = sourcePosts.map { post ->
            val remote = ImageRef("https://unavailable.invalid/${post.id.sourcePostId}.png", null, "image/png")
            post.copy(full = remote, media = listOf(remote), mediaCount = 1)
        }
        val collection = io {
            container.content.ensureCodex(profileScopedCodexId("profile-main", "offline"), "Offline collection")
                .also { container.content.addItems(it.codexId, snapshots) }
        }
        launch()
        tab("Codex")
        compose.onNodeWithContentDescription("Actions for Offline collection").performClick()
        compose.onNodeWithText("Make available offline").performClick()
        val offline = requireNotNull(container.features.offlineMedia)
        compose.waitUntil(10_000) { offline.store.snapshot.value.postCount == 2 }
        compose.onNodeWithText("2 posts available offline").assertIsDisplayed()
        compose.onNodeWithText("Clear disposable cache").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Disposable cache cleared. Offline copies are still available.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        val localPaths = io { sourcePosts.map { requireNotNull(offline.find(it.id)?.full?.localPath) } }
        assertTrue(localPaths.all { File(it).isFile })
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        coldGraphRelaunch()
        tab("Codex")
        compose.onNodeWithText("Offline collection").performClick()
        val ordered = io { container.content.observeCodexPosts(collection.codexId, CodexSortMode.NEWEST_SAVED).first() }
        container.registry.resolveRequests.clear()
        compose.onNodeWithTag(searchCardTestTag(ordered.first().id)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(requireNotNull(ordered.first().title)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(requireNotNull(ordered.first().title)).performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) {
            io { container.data.recentsRepository.observeWatchedPosts().first().any { it.post.id == ordered.last().id } }
        }
        compose.onNodeWithContentDescription(requireNotNull(ordered.last().title)).assertIsDisplayed()
        val viewed = io { container.data.recentsRepository.observeWatchedPosts().first() }
        assertEquals(ordered.map { it.id }.toSet(), viewed.map { it.post.id }.toSet())
        assertTrue(viewed.all { it.post.full?.localPath in localPaths })
        assertTrue(container.registry.resolveRequests.isEmpty())
    }
}
