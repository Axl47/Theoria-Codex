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
        first.addItem(created.codexId, samplePost("1", localPath = null))

        val second = FileBackedCodexRepository(dir)

        assertEquals(1, second.observeCodices().first().size)
        assertEquals(1, second.observeCodexItems(created.codexId).first().size)
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
        first.updateSettings {
            it.copy(
                cache = it.cache.copy(cacheFullImageOnSave = true),
                lastSelectedTabRoute = "codex",
            )
        }

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()

        assertTrue(loaded.cache.cacheFullImageOnSave)
        assertEquals("codex", loaded.lastSelectedTabRoute)
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

    private fun samplePost(id: String, localPath: String?): Post {
        return Post(
            id = PostId(SourceKey.PIXIV, id),
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
