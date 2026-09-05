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
    fun `empty cached bytes are replaced by the refreshed remote pointer`() = runTest {
        val dir = tempDir("cache-thumbnail-refresh-")
        val sourceFile = File(dir, "stale-thumb.jpg").apply { writeText("stale-image") }
        val repository = FileBackedCacheRepository(dir)
        val stalePost = samplePost("1", sourceFile.absolutePath)
        repository.cacheThumbnail(stalePost)
        dir.resolve("cache/thumbnails/PIXIV_1.jpg").writeBytes(byteArrayOf())

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

    @Test
    fun `sparse saves preserve downloaded thumbnail and full bytes until usable replacements arrive`() = runTest {
        val dir = tempDir("cache-sparse-save-")
        val original = dir.resolve("original.jpg").apply { writeText("downloaded-image") }
        val empty = dir.resolve("empty.jpg").apply { writeBytes(byteArrayOf()) }
        val repository = FileBackedCacheRepository(dir)
        val rich = samplePost("1", original.absolutePath).let { post ->
            post.copy(full = requireNotNull(post.full).copy(localPath = original.absolutePath))
        }
        repository.cacheThumbnail(rich)
        repository.cacheFull(rich)
        original.delete()

        for (localPath in listOf(null, original.absolutePath, empty.absolutePath)) {
            val sparse = rich.copy(
                preview = rich.preview.copy(localPath = localPath, url = "https://example.com/new.webp"),
                full = requireNotNull(rich.full).copy(localPath = localPath, url = "https://example.com/new-full.webp"),
            )
            repository.cacheThumbnail(sparse)
            repository.cacheFull(sparse)
            for (cache in listOf("thumbnails", "full")) {
                val files = dir.resolve("cache/$cache").listFiles().orEmpty()
                assertEquals(listOf("PIXIV_1.jpg"), files.map(File::getName))
                assertEquals("downloaded-image", files.single().readText())
            }
        }

        val replacement = dir.resolve("replacement.webp").apply { writeText("new-image") }
        repository.cacheThumbnail(rich.copy(preview = rich.preview.copy(localPath = replacement.absolutePath)))
        val thumbnail = dir.resolve("cache/thumbnails").listFiles().orEmpty().single()
        assertEquals("PIXIV_1.webp", thumbnail.name)
        assertEquals("new-image", thumbnail.readText())
        repository.cacheThumbnail(rich.copy(preview = rich.preview.copy(localPath = thumbnail.absolutePath)))
        assertEquals("new-image", thumbnail.readText())
    }

}
