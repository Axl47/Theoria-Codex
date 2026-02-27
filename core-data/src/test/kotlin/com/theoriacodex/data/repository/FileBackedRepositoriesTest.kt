package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBackedRepositoriesTest {
    @Test
    fun `codex repository persists codex and items across instances`() = runTest {
        val dir = Files.createTempDirectory("codex-store-").toFile()
        val first = FileBackedCodexRepository(dir)
        val created = first.createCodex("Saved")
        first.addItem(created.codexId, samplePost("1", localPath = null, source = SourceKey.PIXIV))
        first.addItem(created.codexId, samplePost("2", localPath = null, source = SourceKey.AIBOORU))

        val second = FileBackedCodexRepository(dir)
        val bySource = second.observeCodexPosts(created.codexId, CodexSortMode.BY_SOURCE).first()

        assertEquals(1, second.observeCodices().first().size)
        assertEquals(2, second.observeCodexItems(created.codexId).first().size)
        assertEquals(SourceKey.AIBOORU, bySource.first().id.source)
        assertNotNull(second.getPost(PostId(SourceKey.PIXIV, "1")))
    }

    @Test
    fun `query repository persists applied query and scroll offsets`() = runTest {
        val dir = Files.createTempDirectory("query-store-").toFile()
        val first = FileBackedQueryRepository(dir)
        val query = Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = listOf("landscape"),
            excludeTags = listOf("comic"),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = 10,
        )
        first.upsertAppliedQuery("source:PIXIV", query)
        first.upsertScrollOffset("qhash", 320)

        val second = FileBackedQueryRepository(dir)

        assertNotNull(second.observeAppliedQuery("source:PIXIV").first())
        assertEquals(320, second.getScrollOffset("qhash"))
    }

    @Test
    fun `settings repository persists updates`() = runTest {
        val dir = Files.createTempDirectory("settings-store-").toFile()
        val first = FileBackedSettingsRepository(dir)
        first.setEnabledSources(setOf(SourceKey.PIXIV, SourceKey.GELBOORU))
        first.setSourceWeights(mapOf(SourceKey.PIXIV to 4.0, SourceKey.GELBOORU to 1.0))
        first.setCacheFullImageOnSave(true)
        first.setScenarioPreset(ScenarioPreset.EMPTY_RESULTS)
        first.setLastTab("codex")
        first.setActiveProfile(UserProfile.USER_2)

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()

        assertTrue(loaded.cache.cacheFullImageOnSave)
        assertEquals(ScenarioPreset.EMPTY_RESULTS, loaded.scenarioPreset)
        assertEquals("codex", loaded.lastSelectedTabRoute)
        assertEquals(UserProfile.USER_2, loaded.activeProfile)
        val total = loaded.runtime.sourceWeights.values.sum()
        assertEquals(1.0, total, 0.0001)
    }

    @Test
    fun `likes repository persists toggles and profile isolation`() = runTest {
        val dir = Files.createTempDirectory("likes-store-").toFile()
        val first = FileBackedLikesRepository(dir)
        val pixivPost = samplePost("10", localPath = null, source = SourceKey.PIXIV)
        val gelbooruPost = samplePost("11", localPath = null, source = SourceKey.GELBOORU)

        first.toggleLike(
            profile = UserProfile.USER_1,
            postId = pixivPost.id,
            tags = listOf("cloud", "sky"),
        )
        first.toggleLike(
            profile = UserProfile.USER_2,
            postId = gelbooruPost.id,
            tags = listOf("sunset"),
        )

        val second = FileBackedLikesRepository(dir)
        val user1Likes = second.observeLikes(UserProfile.USER_1).first()
        val user2Likes = second.observeLikes(UserProfile.USER_2).first()

        assertEquals(1, user1Likes.size)
        assertEquals(pixivPost.id, user1Likes.first().postId)
        assertEquals(listOf("cloud", "sky"), user1Likes.first().tags)
        assertEquals(1, user2Likes.size)
        assertEquals(gelbooruPost.id, user2Likes.first().postId)

        second.clearLikes(UserProfile.USER_1)
        val third = FileBackedLikesRepository(dir)
        assertTrue(third.observeLikes(UserProfile.USER_1).first().isEmpty())
        assertEquals(1, third.observeLikes(UserProfile.USER_2).first().size)
    }

    @Test
    fun `cache repository writes entries and survives restart`() = runTest {
        val dir = Files.createTempDirectory("cache-store-").toFile()
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
    fun `ui restore repository persists tab scroll and viewer context`() = runTest {
        val dir = Files.createTempDirectory("ui-restore-store-").toFile()
        val first = FileBackedUiRestoreRepository(dir)
        val context = ViewerLaunchContext(
            queryHash = "qhash",
            startIndex = 4,
            streamSource = ViewerStreamSource.CODEX,
            scrollOffsetHint = 90,
        )

        first.setLastTab("settings")
        first.setSearchScrollState("qhash", SearchScrollState(firstVisibleItemIndex = 3, firstVisibleItemOffsetPx = 28))
        first.setViewerLaunchContext(context)

        val second = FileBackedUiRestoreRepository(dir)

        assertEquals("settings", second.getLastTab())
        assertEquals(3, second.getSearchScrollState("qhash")?.firstVisibleItemIndex)
        assertEquals(context, second.observeViewerLaunchContext().first())
    }

    private fun samplePost(id: String, localPath: String?, source: SourceKey = SourceKey.PIXIV): Post {
        return Post(
            id = PostId(source, id),
            preview = ImageRef(url = "https://example.com/$id.jpg", localPath = localPath, mime = "image/jpeg"),
            full = ImageRef(url = "https://example.com/full/$id.jpg", localPath = null, mime = "image/jpeg"),
            pageUrl = "https://example.com/post/$id",
            width = 100,
            height = 100,
            canonicalTags = listOf("landscape"),
            rawTags = listOf("landscape"),
            authorName = "artist",
            createdAtEpochMs = 1L,
        )
    }
}
