package com.theoriacodex.data.repository

import com.theoriacodex.data.testing.RecordingIoDispatcher
import com.theoriacodex.data.testing.ControllableIoDispatcher
import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class FileBackedAuxiliaryRepositoriesTest : FileBackedRepositoryTestFixture() {
    @Test
    fun `likes repository persists toggles and profile isolation`() = runTest {
        val dir = tempDir("likes-store-")
        val first = FileBackedLikesRepository(dir)
        val pixivPost = samplePost("10", localPath = null, source = SourceKey.PIXIV)
        val gelbooruPost = samplePost("11", localPath = null, source = SourceKey.GELBOORU)

        first.toggleLike(
            profileId = "profile-main",
            postId = pixivPost.id,
            tags = listOf("cloud", "sky"),
        )
        first.toggleLike(
            profileId = "profile-alt",
            postId = gelbooruPost.id,
            tags = listOf("sunset"),
        )

        val second = FileBackedLikesRepository(dir)
        val user1Likes = second.observeLikes("profile-main").first()
        val user2Likes = second.observeLikes("profile-alt").first()

        assertEquals(1, user1Likes.size)
        assertEquals(pixivPost.id, user1Likes.first().postId)
        assertEquals(listOf("cloud", "sky"), user1Likes.first().tags)
        assertEquals(1, user2Likes.size)
        assertEquals(gelbooruPost.id, user2Likes.first().postId)

        second.clearLikes("profile-main")
        val third = FileBackedLikesRepository(dir)
        assertTrue(third.observeLikes("profile-main").first().isEmpty())
        assertEquals(1, third.observeLikes("profile-alt").first().size)
    }

    @Test
    fun `cache repository writes entries and survives restart`() = runTest {
        val dir = tempDir("cache-store-")
        val sourceFile = File(dir, "source-thumb.jpg")
        sourceFile.writeText("image-bytes")

        val first = FileBackedCacheRepository(dir)
        first.cacheThumbnail(samplePost("1", sourceFile.absolutePath))

        val second = FileBackedCacheRepository(dir)
        val snapshot = second.observeSnapshot().first()

        assertEquals(1, snapshot.thumbnailCount)
        assertEquals(0, snapshot.fullImageCount)
    }

    @Test
    fun `cache repository replaces stale local thumbnail with refreshed remote pointer`() = runTest {
        val dir = tempDir("cache-thumbnail-refresh-")
        val sourceFile = File(dir, "stale-thumb.jpg").apply { writeText("stale-image") }
        val repository = FileBackedCacheRepository(dir)
        val stalePost = samplePost("1", sourceFile.absolutePath)
        repository.cacheThumbnail(stalePost)

        val refreshedPost = stalePost.copy(
            preview = stalePost.preview.copy(
                url = "https://example.com/refreshed-thumb.webp",
                localPath = null,
                mime = "image/webp",
            ),
        )
        repository.cacheThumbnail(refreshedPost)

        val entries = dir.resolve("cache/thumbnails").listFiles().orEmpty()
        assertEquals(listOf("PIXIV_1.url"), entries.map(File::getName))
        assertEquals("https://example.com/refreshed-thumb.webp", entries.single().readText())
    }

    @Test
    fun `ui restore repository persists tab scroll and viewer context`() = runTest {
        val dir = tempDir("ui-restore-store-")
        val first = FileBackedUiRestoreRepository(dir)
        val context = ViewerLaunchContext(
            queryHash = "recents:codex",
            startIndex = 4,
            streamSource = ViewerStreamSource.RECENTS,
            scrollOffsetHint = 90,
            recentsSection = RecentPostSection.CODEX,
        )

        first.setLastTab("settings")
        first.setSearchScrollState("qhash", SearchScrollState(firstVisibleItemIndex = 3, firstVisibleItemOffsetPx = 28))
        first.setViewerLaunchContext(context)

        val second = FileBackedUiRestoreRepository(dir)

        assertEquals("settings", second.getLastTab())
        assertEquals(3, second.getSearchScrollState("qhash")?.firstVisibleItemIndex)
        assertEquals(context, second.observeViewerLaunchContext().first())
    }

    @Test
    fun `ui restore repository persists one-time legacy tab migration across reconstruction`() = runTest {
        val dir = tempDir("ui-restore-legacy-tab-")
        val first = FileBackedUiRestoreRepository(dir)

        assertEquals("codex", first.migrateLegacyLastTab("codex"))

        val second = FileBackedUiRestoreRepository(dir)
        assertEquals("codex", second.migrateLegacyLastTab("search"))
        assertEquals("codex", second.getLastTab())
    }

}
