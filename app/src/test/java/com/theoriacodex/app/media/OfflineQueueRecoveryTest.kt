package com.theoriacodex.app.media

import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.data.repository.OfflinePendingJobStore
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineQueueRecoveryTest {
    @Test fun `a reopened offline owner retains verified completion and resumes only unfinished acquisition`() = runTest {
        val directory = Files.createTempDirectory("offline-recovery").toFile()
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val secondScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val store = OfflineMediaStore(directory.resolve("media"), ioDispatcher = StandardTestDispatcher(testScheduler))
            val queue = OfflinePendingJobStore(directory.resolve("queue.json"))
            val posts = listOf(post("a"), post("b"), post("c")).associateBy(Post::id)
            val bStarted = CompletableDeferred<Unit>()
            val acquired = mutableListOf<String>()
            val first = OfflineMediaCoordinator(store, firstScope, { it }, { it.media }, { post, _, output ->
                if (post.id.sourcePostId == "b") { bStarted.complete(Unit); awaitCancellation() }
                acquired += post.id.sourcePostId
                output.writeText("verified bytes")
            }, pendingStore = queue)
            first.makeAvailableOffline(AUTOMATIC_OFFLINE_OWNER, posts.values.take(2))
            bStarted.await()
            first.saveOnCollectionSave(posts.values.last())
            while (queue.load()[AUTOMATIC_OFFLINE_OWNER]?.size != 3) kotlinx.coroutines.yield()
            assertFalse(directory.resolve("queue.json").readText().contains("signed-token"))
            firstScope.cancel()
            val reopenedStore = OfflineMediaStore(directory.resolve("media"), ioDispatcher = StandardTestDispatcher(testScheduler))
            reopenedStore.load()
            val reopened = OfflineMediaCoordinator(reopenedStore, secondScope, { posts.getValue(it.id) }, { it.media },
                { post, _, output -> acquired += post.id.sourcePostId; output.writeText("verified bytes") }, pendingStore = queue)
            reopened.recoverPendingJobs()
            reopened.jobs.first { it[AUTOMATIC_OFFLINE_OWNER]?.isRunning == false }
            assertEquals(listOf("a", "b", "c"), acquired)
            assertEquals(3, reopenedStore.snapshot.value.postCount)
        } finally { firstScope.cancel(); secondScope.cancel(); directory.deleteRecursively() }
    }

    @Test fun `recovery skips a collection owner that no longer exists`() = runTest {
        val directory = Files.createTempDirectory("offline-owner-recovery").toFile()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val pending = OfflinePendingJobStore(directory.resolve("queue.json"))
            pending.save(mapOf("codex:removed" to listOf(post("a").id)))
            val owner = OfflineMediaCoordinator(OfflineMediaStore(directory.resolve("media")), scope,
                { error("Deleted collection must not resolve") }, { error("Must not select") },
                { _, _, _ -> error("Must not acquire") }, pendingStore = pending, ownerExists = { false })
            owner.recoverPendingJobs()
            assertEquals(emptyMap<String, OfflineMediaJob>(), owner.jobs.value)
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun `recovery registers every retained owner before checkpointing acquisition`() = runTest {
        val directory = Files.createTempDirectory("offline-multiple-owners").toFile()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val pending = OfflinePendingJobStore(directory.resolve("queue.json"))
            pending.save(mapOf("codex:first" to listOf(post("a").id), "codex:second" to listOf(post("b").id)))
            val started = CompletableDeferred<Unit>()
            val owner = OfflineMediaCoordinator(OfflineMediaStore(directory.resolve("media")), scope,
                { started.complete(Unit); awaitCancellation() }, { it.media }, { _, _, _ -> error("Not resolved") },
                pendingStore = pending, ownerExists = { kotlinx.coroutines.yield(); true })
            owner.recoverPendingJobs()
            started.await()
            assertEquals(setOf("codex:first", "codex:second"), pending.load().keys)
            owner.cancel("codex:first")
            while ("codex:first" in pending.load()) kotlinx.coroutines.yield()
            assertEquals(setOf("codex:second"), pending.load().keys)
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun `a failed removal checkpoint preserves completed offline copies`() = runTest {
        for (clearAll in listOf(false, true)) {
            val directory = Files.createTempDirectory("offline-remove-intent").toFile()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val parent = directory.resolve("queue").apply { mkdirs() }
                val pending = OfflinePendingJobStore(parent.resolve("intent.json"))
                val media = OfflineMediaStore(directory.resolve("media"), ioDispatcher = StandardTestDispatcher(testScheduler))
                val blocked = CompletableDeferred<Unit>()
                val owner = OfflineMediaCoordinator(media, scope, { it }, { it.media }, { post, _, output ->
                    if (post.id.sourcePostId == "b") { blocked.complete(Unit); awaitCancellation() }
                    output.writeText("completed bytes")
                }, pendingStore = pending)
                owner.makeAvailableOffline("codex:keep", listOf(post("a"), post("b")))
                blocked.await()
                org.junit.Assert.assertTrue(parent.renameTo(directory.resolve("saved-queue")))
                parent.writeText("storage became unavailable")
                val result = runCatching { if (clearAll) owner.clear() else owner.remove("codex:keep") }
                org.junit.Assert.assertTrue(result.exceptionOrNull() is java.io.IOException)
                assertEquals(1, media.snapshot.value.postCount)
            } finally { scope.cancel(); directory.deleteRecursively() }
        }
    }

    private fun post(id: String): Post = Post(PostId(SourceKey.PIXIV, id), ImageRef(null, null, null), null,
        media = listOf(ImageRef("https://example.test/$id.jpg?signed-token=secret", null, "image/jpeg")),
        pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null)
}
