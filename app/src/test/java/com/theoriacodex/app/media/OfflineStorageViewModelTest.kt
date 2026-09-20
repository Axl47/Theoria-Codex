package com.theoriacodex.app.media

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineStorageViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val codices = InMemoryCodexRepository()

    @After fun tearDown() { scope.cancel() }

    @Test fun `removal requires confirmation and preserves bytes retained by another collection`() = runBlocking {
        withTimeout(10_000) {
            codices.ensureCodex("a", "First")
            codices.ensureCodex("b", "Second")
            val coordinator = coordinator()
            val post = post()
            coordinator.store.save(post, post.media, "codex:a") { _, file -> file.writeText("media") }
            coordinator.store.retain(post.id, "codex:b")
            val owner = owner(coordinator)
            val initial = owner.state.first { it.owners.size == 2 }
            assertEquals(listOf("First", "Second"), initial.owners.map { it.name })
            assertEquals(1, initial.offline.postCount)
            assertEquals(1, initial.offline.mediaCount)

            owner.requestRemove("codex:a")
            owner.state.first { it.pendingRemoval != null }
            assertNotNull(coordinator.find(post.id))
            owner.dismissRemoval()
            owner.state.first { it.pendingRemoval == null }
            assertEquals(setOf("codex:a", "codex:b"), coordinator.store.snapshot.value.postsByOwner.keys)
            owner.requestRemove("codex:a")
            owner.confirmRemoval()
            owner.state.first { !it.working && it.owners.map(OfflineStorageOwner::id) == listOf("codex:b") }
            assertNotNull(coordinator.find(post.id))

            owner.requestClearOffline()
            owner.confirmRemoval()
            owner.state.first { !it.working && it.offline.postCount == 0 }
            assertNull(coordinator.find(post.id))
        }
    }

    @Test fun `disposable cache clearing reports retained active media without deleting offline copies`() = runBlocking {
        withTimeout(10_000) {
            val coordinator = coordinator()
            val post = post()
            coordinator.store.save(post, post.media, AUTOMATIC_OFFLINE_OWNER) { _, file -> file.writeText("media") }
            var usage = DisposableMediaCacheSnapshot(imageBytes = 100, animationBytes = 20)
            val owner = OfflineStorageViewModel(
                codices, coordinator, readDisposableUsage = { usage },
                clearDisposable = { usage = DisposableMediaCacheSnapshot(animationBytes = 20); false },
                scopeOverride = scope,
            )
            owner.open()
            val opened = owner.state.first { it.isOpen && !it.refreshing && it.disposable != null }
            assertEquals("Saved posts", opened.owners.single().name)
            assertEquals(120L, opened.disposable?.totalBytes)
            owner.clearDisposableCache()
            val cleared = owner.state.first { !it.working && it.disposable?.totalBytes == 20L }
            assertTrue(cleared.message.orEmpty().contains("currently in use"))
            assertNotNull(coordinator.find(post.id))
            assertEquals(1, cleared.offline.postCount)
        }
    }

    @Test fun `collection preparation survives sheet dismissal and publishes coordinator progress`() = runBlocking {
        withTimeout(10_000) {
            codices.ensureCodex("a", "First")
            codices.addItems("a", listOf(post()))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = object : CodexRepository by codices {
                override fun observeCodexPosts(codexId: String, sort: CodexSortMode) = flow {
                    started.complete(Unit)
                    release.await()
                    emit(codices.observeCodexPosts(codexId, sort).first())
                }
            }
            val coordinator = coordinator()
            val owner = owner(coordinator, repository)
            owner.makeAvailableOffline("a")
            started.await()
            val preparing = owner.state.first { it.owners.any(OfflineStorageOwner::preparing) }
            assertEquals("First", preparing.owners.single().name)
            owner.dismiss()
            release.complete(Unit)
            val completed = owner.state.first { it.owners.singleOrNull()?.job?.isRunning == false && it.offline.postCount == 1 }
            assertEquals(1, completed.offline.postCount)
            assertEquals(1, completed.owners.single().job?.completedPosts)
            assertTrue(!completed.isOpen)
        }
    }

    @Test fun `clearing offline cancels collection preparation before it can start a new download`() = runBlocking {
        withTimeout(10_000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val repository = object : CodexRepository by codices {
                override fun observeCodexPosts(codexId: String, sort: CodexSortMode) = flow {
                    try {
                        started.complete(Unit)
                        release.await()
                        emit(listOf(post()))
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }
            val coordinator = coordinator()
            val owner = owner(coordinator, repository)
            owner.makeAvailableOffline("a")
            started.await()
            owner.requestClearOffline()
            owner.confirmRemoval()
            cancelled.await()
            owner.state.first { !it.working && it.message == "Offline copies removed." }
            release.complete(Unit)
            assertTrue(coordinator.jobs.value.isEmpty())
            assertTrue(owner.state.value.preparingOwners.isEmpty())
        }
    }

    private fun coordinator() = OfflineMediaCoordinator(
        store = OfflineMediaStore(temporary.newFolder()), scope = scope,
        resolve = { it }, selectMedia = { it.media }, acquire = { _, _, output -> output.writeText("media") },
    )

    private fun owner(coordinator: OfflineMediaCoordinator, repository: CodexRepository = codices) = OfflineStorageViewModel(
        repository, coordinator, readDisposableUsage = { DisposableMediaCacheSnapshot() },
        clearDisposable = { true }, scopeOverride = scope,
    )

    private fun post(): Post {
        val image = ImageRef(url = "https://example.test/full.jpg", localPath = null, mime = "image/jpeg")
        return testPost(sourcePostId = "offline").copy(full = image, media = listOf(image), mediaCount = 1)
    }
}
