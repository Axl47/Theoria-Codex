package com.theoriacodex.data.repository

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

internal class FileBackedCacheRepositoryTest : FileBackedRepositoryTestFixture() {
    @Test
    fun `cache repository writes entries and survives restart`() = runTest {
        val dir = tempDir("cache-store-")
        val sourceFile = File(dir, "source-thumb.jpg")
        sourceFile.writeText("image-bytes")

        val first = FileBackedCacheRepository(dir)
        first.cacheThumbnail(samplePost("1", sourceFile.absolutePath))

        val snapshot = FileBackedCacheRepository(dir).observeSnapshot().first()

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

        repository.cacheThumbnail(
            stalePost.copy(
                preview = stalePost.preview.copy(
                    url = "https://example.com/refreshed-thumb.webp",
                    localPath = null,
                    mime = "image/webp",
                ),
            ),
        )

        val entries = dir.resolve("cache/thumbnails").listFiles().orEmpty()
        assertEquals(listOf("PIXIV_1.url"), entries.map(File::getName))
        assertEquals("https://example.com/refreshed-thumb.webp", entries.single().readText())
    }
}
