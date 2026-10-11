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
            val posts = listOf(post("a"), post("b")).associateBy(Post::id)
            val bStarted = CompletableDeferred<Unit>()
            val acquired = mutableListOf<String>()
            val first = OfflineMediaCoordinator(store, firstScope, { it }, { it.media }, { post, _, output ->
                if (post.id.sourcePostId == "b") { bStarted.complete(Unit); awaitCancellation() }
                acquired += post.id.sourcePostId
                output.writeText("verified bytes")
            }, pendingStore = queue)
            first.makeAvailableOffline("codex:keep", posts.values.toList())
            bStarted.await()
            assertFalse(directory.resolve("queue.json").readText().contains("signed-token"))
            firstScope.cancel()
            val reopened = OfflineMediaCoordinator(store, secondScope, { posts.getValue(it.id) }, { it.media },
                { post, _, output -> acquired += post.id.sourcePostId; output.writeText("verified bytes") }, pendingStore = queue)
            reopened.recoverPendingJobs()
            reopened.jobs.first { it["codex:keep"]?.isRunning == false }
            assertEquals(listOf("a", "b"), acquired)
            assertEquals(2, store.snapshot.value.postCount)
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

    private fun post(id: String): Post = Post(PostId(SourceKey.PIXIV, id), ImageRef(null, null, null), null,
        media = listOf(ImageRef("https://example.test/$id.jpg?signed-token=secret", null, "image/jpeg")),
        pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null)
}
