package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Post
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class OfflineMediaStoreTest : FileBackedRepositoryTestFixture() {
    @Test
    fun `complete galleries survive restart and share one copy across collection owners`() = runTest {
        val directory = tempDir("offline")
        val post = gallery()
        val store = OfflineMediaStore(directory)
        val completed = store.save(post, post.media, "codex:a") { ref, file -> file.writeText(ref.url!!) }
        assertEquals(2, completed.media.size)
        assertEquals(completed.media.first(), completed.full)
        assertTrue(completed.media.all { File(it.localPath!!).readText() == it.url })
        assertEquals(completed, store.retain(post.id, "codex:b"))

        val restored = OfflineMediaStore(directory)
        restored.refresh()
        assertEquals(completed, restored.find(post.id))
        assertEquals(mapOf("codex:a" to 1, "codex:b" to 1), restored.snapshot.value.postsByOwner)
        assertEquals(2, restored.snapshot.value.mediaCount)
        assertEquals(directory.walkTopDown().filter(File::isFile).sumOf(File::length), restored.snapshot.value.bytes)

        restored.removeOwner("codex:a")
        assertNotNull(restored.find(post.id))
        restored.removeOwner("codex:b")
        assertNull(restored.find(post.id))
        assertEquals(0L, restored.snapshot.value.bytes)
    }

    @Test
    fun `failed replacement and cancelled gallery leave completed bytes intact`() = runTest {
        val directory = tempDir("failed")
        val post = gallery()
        val store = OfflineMediaStore(directory)
        val completed = store.save(post, post.media, "saved-posts") { _, file -> file.writeText("original") }
        var failed = false
        try {
            store.save(post, post.media, "codex:b") { _, file ->
                file.writeText("partial")
                throw IOException("disconnected")
            }
        } catch (_: IOException) { failed = true }
        assertTrue(failed)
        assertEquals(completed, store.find(post.id))
        val started = CompletableDeferred<Unit>()
        val cancelled = async {
            store.save(post, post.media, "codex:b") { _, file ->
                file.writeText("partial")
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        cancelled.cancelAndJoin()
        assertEquals(completed, store.find(post.id))
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(".pending-") })
    }

    @Test
    fun `clear or owner removal invalidates an already running acquisition`() = runTest {
        for (clearAll in listOf(false, true)) {
            val store = OfflineMediaStore(tempDir("clear-$clearAll"))
            val post = gallery()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val saving = async {
                runCatching {
                    store.save(post, post.media, "codex:a") { _, file ->
                        started.complete(Unit)
                        finish.await()
                        file.writeText("complete")
                    }
                }
            }
            started.await()
            if (clearAll) store.clear() else store.removeOwner("codex:a")
            finish.complete(Unit)
            assertTrue(saving.await().isFailure)
            assertNull(store.find(post.id))
        }
    }

    @Test
    fun `missing truncated and incomplete media never appears available`() = runTest {
        val directory = tempDir("integrity")
        val store = OfflineMediaStore(directory)
        val post = gallery()
        assertNull(store.retain(post.id, "missing"))
        try {
            store.save(post, post.media.take(1), "codex:a") { _, file -> file.writeText("bytes") }
            error("Incomplete gallery was accepted")
        } catch (_: IllegalArgumentException) { }
        val saved = store.save(post, post.media, "codex:a") { _, file -> file.writeText("complete") }
        File(saved.media.last().localPath!!).writeText("short")
        assertNull(store.find(post.id))
        store.refresh()
        assertEquals(0, store.snapshot.value.postCount)
        assertTrue(store.snapshot.value.bytes > 0)
        store.clear()
        assertEquals(0L, store.snapshot.value.bytes)
    }

    @Test
    fun `a later owner retains the completed generation and all previously returned paths`() = runTest {
        val directory = tempDir("replace")
        val store = OfflineMediaStore(directory)
        val post = gallery()
        val first = store.save(post, post.media, "codex:a") { _, file -> file.writeText("first") }
        store.save(post, post.media, "codex:b") { _, file -> file.writeText("second") }
        assertTrue(first.media.all { File(it.localPath!!).readText() == "first" })
        assertEquals(mapOf("codex:a" to 1, "codex:b" to 1), store.snapshot.value.postsByOwner)
        assertEquals(1, directory.listFiles().orEmpty().single().listFiles().orEmpty().count(File::isDirectory))
    }

    @Test
    fun `offline overlays strip only owned paths even after media was removed`() = runTest {
        val directory = tempDir("strip")
        val store = OfflineMediaStore(directory)
        val post = gallery()
        val saved = store.save(post, post.media, "codex:a") { _, file -> file.writeText("bytes") }
        store.clear()
        val unrelated = post.media.last().copy(localPath = File(directory.parentFile, "fixture.png").absolutePath)
        val overlaid = saved.copy(media = saved.media + unrelated)
        val clean = store.withoutOfflineLocations(overlaid)
        assertNull(clean.preview.localPath)
        assertNull(clean.full?.localPath)
        assertTrue(clean.media.take(2).all { it.localPath == null })
        assertEquals(unrelated, clean.media.last())
        assertEquals(post.full?.url, clean.full?.url)
        assertEquals(clean, store.withoutOfflineLocations(clean))
    }

    private fun gallery(): Post {
        val post = samplePost("42", null)
        val first = requireNotNull(post.full)
        return post.copy(media = listOf(first, first.copy(url = "https://example.test/page2.png")), mediaCount = 2)
    }
}
