package com.theoriacodex.app.journeys

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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
            io { container.data.recentsRepository.observeWatchedPosts().first().any { it.post.id == ordered.first().id } } &&
                compose.onAllNodesWithContentDescription("Media actions", useUnmergedTree = true)
                    .fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithContentDescription(requireNotNull(ordered.first().title)).assertIsDisplayed()
        val activeMedia = compose.onNodeWithContentDescription("Media actions", useUnmergedTree = true)
        activeMedia.assertIsDisplayed()
        // The active overlay owns the swipe detector; image semantics can exist during route entry.
        // Stay away from system back-gesture edges while crossing well beyond the 12% paging threshold.
        activeMedia.performTouchInput {
            swipe(
                start = Offset(width * 0.85f, height * 0.5f),
                end = Offset(width * 0.15f, height * 0.5f),
                durationMillis = 400L,
            )
        }
        compose.waitUntil(10_000) {
            io { container.data.recentsRepository.observeWatchedPosts().first().any { it.post.id == ordered.last().id } }
        }
        compose.onNodeWithContentDescription(requireNotNull(ordered.last().title)).assertIsDisplayed()
        val viewed = io { container.data.recentsRepository.observeWatchedPosts().first() }
        assertEquals(ordered.map { it.id }.toSet(), viewed.map { it.post.id }.toSet())
        viewed.forEach { entry ->
            val original = sourcePosts.single { it.id == entry.post.id }
            assertEquals(original.full?.mime, entry.post.full?.mime)
            val persistedMedia = listOfNotNull(entry.post.preview, entry.post.full) + entry.post.media
            assertTrue(persistedMedia.none { it.localPath in localPaths })
        }
        val retainedOffline = requireNotNull(container.features.offlineMedia)
        assertEquals(localPaths.toSet(), io {
            sourcePosts.map { requireNotNull(retainedOffline.find(it.id)?.full?.localPath) }.toSet()
        })
        assertTrue(localPaths.all { File(it).isFile })
        assertTrue(container.registry.resolveRequests.isEmpty())
    }
}
