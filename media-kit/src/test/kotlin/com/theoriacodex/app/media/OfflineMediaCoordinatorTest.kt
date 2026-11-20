package com.theoriacodex.app.media

import com.theoriacodex.data.repository.FileBackedCacheRepository
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import com.theoriacodex.domain.model.VideoVariant
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineMediaCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `retry skips complete posts and multiple owners share offline media`() = runTest {
        val store = OfflineMediaStore(temporary.newFolder(), ioDispatcher = StandardTestDispatcher(testScheduler))
        var fail = true
        val acquisitions = mutableListOf<String>()
        val coordinator = OfflineMediaCoordinator(store, backgroundScope, { it }, { it.media }, { post, _, file ->
            acquisitions += post.id.sourcePostId
            if (post.id.sourcePostId == "b" && fail) throw IOException("offline")
            file.writeText("bytes")
        })
        coordinator.makeAvailableOffline("codex:a", listOf(post("a"), post("b"), post("a")))
        val first = coordinator.jobs.first { it["codex:a"]?.isRunning == false }.getValue("codex:a")
        assertEquals(1, first.completedPosts)
        assertEquals(setOf(post("b").id), first.failedPosts)
        assertNotNull(first.failureMessage)
        fail = false
        coordinator.retry("codex:a")
        val retried = coordinator.jobs.first { it["codex:a"]?.let { job -> !job.isRunning && job.failedPosts.isEmpty() } == true }
        assertEquals(2, retried.getValue("codex:a").completedPosts)
        assertEquals(listOf("a", "b", "b"), acquisitions)
        coordinator.makeAvailableOffline("codex:b", listOf(post("a")))
        coordinator.jobs.first { it["codex:b"]?.isRunning == false }
        coordinator.remove("codex:a")
        assertNotNull(coordinator.find(post("a").id))
        assertNull(coordinator.find(post("b").id))
        coordinator.clear()
        assertEquals(0, store.snapshot.value.postCount)
        assertTrue(coordinator.jobs.value.isEmpty())
    }

    @Test
    fun `deadline fails one post while explicit cancellation remains cancellation`() = runTest {
        val started = CompletableDeferred<Unit>()
        val coordinator = OfflineMediaCoordinator(
            OfflineMediaStore(temporary.newFolder(), ioDispatcher = StandardTestDispatcher(testScheduler)), backgroundScope, { post ->
                if (post.id.sourcePostId == "slow") {
                    started.complete(Unit)
                    awaitCancellation()
                }
                post
            }, { it.media }, { _, _, file -> file.writeText("bytes") }, postTimeoutMs = 50L,
        )
        coordinator.makeAvailableOffline("timed", listOf(post("slow"), post("good")))
        started.await()
        val timed = coordinator.jobs.first { it["timed"]?.isRunning == false }.getValue("timed")
        assertEquals(1, timed.completedPosts)
        assertEquals(setOf(post("slow").id), timed.failedPosts)
        assertFalse(timed.isCancelled)

        coordinator.makeAvailableOffline("cancelled", listOf(post("slow")))
        coordinator.cancel("cancelled")
        val cancelled = coordinator.jobs.first { it["cancelled"]?.isCancelled == true }.getValue("cancelled")
        assertTrue(cancelled.failedPosts.isEmpty())
        assertFalse(cancelled.isRunning)
    }

    @Test
    fun `cache on save stores actual bytes independently from disposable caches`() = runTest {
        val store = OfflineMediaStore(temporary.newFolder(), ioDispatcher = StandardTestDispatcher(testScheduler))
        val coordinator = OfflineMediaCoordinator(store, backgroundScope, { it }, { it.media }, { _, _, file ->
            file.writeText("real media")
        })
        val cache = OfflineAwareCacheRepository(FileBackedCacheRepository(temporary.newFolder()), coordinator)
        cache.cacheFull(post("saved"))
        coordinator.jobs.first { it[AUTOMATIC_OFFLINE_OWNER]?.isRunning == false }
        cache.clearFullImageCache()
        assertNotNull(coordinator.find(post("saved").id))
        assertEquals(mapOf(AUTOMATIC_OFFLINE_OWNER to 1), store.snapshot.value.postsByOwner)
    }

    @Test
    fun `automatic saves return promptly accumulate during acquisition and drain without loss`() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val coordinator = OfflineMediaCoordinator(
            OfflineMediaStore(temporary.newFolder(), ioDispatcher = StandardTestDispatcher(testScheduler)), backgroundScope,
            { post ->
                if (post.id.sourcePostId == "first") { started.complete(Unit); finish.await() }
                post
            }, { it.media }, { _, _, file -> file.writeText("bytes") },
        )
        coordinator.saveOnCollectionSave(post("first"))
        started.await()
        coordinator.saveOnCollectionSave(post("second"))
        coordinator.saveOnCollectionSave(post("second"))
        coordinator.saveOnCollectionSave(post("third"))
        assertEquals(3, coordinator.jobs.value.getValue(AUTOMATIC_OFFLINE_OWNER).totalPosts)
        assertEquals(0, coordinator.store.snapshot.value.postCount)
        finish.complete(Unit)
        val completed = coordinator.jobs.first { it[AUTOMATIC_OFFLINE_OWNER]?.isRunning == false }
        assertEquals(3, completed.getValue(AUTOMATIC_OFFLINE_OWNER).completedPosts)
        assertEquals(3, coordinator.store.snapshot.value.postCount)
    }

    @Test
    fun `clear and owner removal cancel automatic saves still resolving and discard queued posts`() = runTest {
        for (clearAll in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val coordinator = OfflineMediaCoordinator(
                OfflineMediaStore(temporary.newFolder(), ioDispatcher = StandardTestDispatcher(testScheduler)), backgroundScope,
                { post ->
                    if (post.id.sourcePostId == "pending") { started.complete(Unit); awaitCancellation() }
                    post
                }, { it.media }, { _, _, file -> file.writeText("bytes") },
            )
            coordinator.saveOnCollectionSave(post("pending"))
            started.await()
            coordinator.saveOnCollectionSave(post("queued"))
            if (clearAll) coordinator.clear() else coordinator.remove(AUTOMATIC_OFFLINE_OWNER)
            assertNull(coordinator.find(post("pending").id))
            assertNull(coordinator.find(post("queued").id))
            assertTrue(coordinator.jobs.value.isEmpty())
        }
    }

    @Test
    fun `offline data saver playback restores original media duration identity`() = runTest {
        val canonical = ImageRef(
            "https://example.test/original.mp4", null, "video/mp4",
            videoVariants = listOf(VideoVariant("https://example.test/small.webm", 480, mime = "video/webm")),
        )
        val source = post("video").copy(full = canonical, media = listOf(canonical), mediaCount = null)
        val store = OfflineMediaStore(temporary.newFolder(), ioDispatcher = StandardTestDispatcher(testScheduler))
        val coordinator = OfflineMediaCoordinator(store, backgroundScope, { source },
            { it.media.map { ref -> ref.withVideoQuality(VideoQuality.DATA_SAVER, metered = true) } },
            { _, _, file -> file.writeText("webm bytes") },
        )
        coordinator.makeAvailableOffline("codex:video", listOf(source))
        coordinator.jobs.first { it["codex:video"]?.isRunning == false }
        val playback = requireNotNull(coordinator.find(source.id))
        assertEquals("video/webm", playback.full?.mime)
        assertEquals("https://example.test/small.webm", playback.full?.url)
        assertEquals(mediaDurationKey(source), mediaDurationKey(coordinator.withoutOfflineLocations(playback)))
        coordinator.clear()
        assertEquals(source, coordinator.withoutOfflineLocations(playback.copy()))
    }

    private fun post(id: String): Post {
        val ref = ImageRef("https://example.test/$id.png", null, "image/png")
        return Post(
            id = PostId(SourceKey.PIXIV, id), preview = ref, full = ref, media = listOf(ref), pageUrl = null,
            width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null,
            createdAtEpochMs = null, mediaCount = 1,
        )
    }
}
