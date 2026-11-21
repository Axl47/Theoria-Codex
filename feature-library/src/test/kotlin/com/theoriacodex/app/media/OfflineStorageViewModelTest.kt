package com.theoriacodex.app.media

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.undo.UndoSlot
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

    @Test fun `removal hides the row until undo or expiry and preserves bytes retained by another Codex`() = runBlocking {
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

            val undo = UndoSlot()
            owner.remove("codex:a", undo)
            owner.state.first { it.owners.map(OfflineStorageOwner::id) == listOf("codex:b") }
            assertEquals(offlineOwnerUndoAnchor("codex:a"), undo.offer.value?.anchor)
            undo.act(checkNotNull(undo.offer.value))
            owner.state.first { it.owners.size == 2 }
            assertEquals(setOf("codex:a", "codex:b"), coordinator.store.snapshot.value.postsByOwner.keys)

            owner.remove("codex:a", undo)
            undo.expire(LIBRARY_TRANSFERS_UNDO_SCOPE)
            owner.state.first { it.hiddenOwners.isEmpty() && it.owners.map(OfflineStorageOwner::id) == listOf("codex:b") }
            assertEquals(setOf("codex:b"), coordinator.store.snapshot.value.postsByOwner.keys)
            assertNotNull(coordinator.find(post.id))

            owner.removeAll(undo)
            undo.expire(LIBRARY_TRANSFERS_UNDO_SCOPE)
            owner.state.first { !it.working && it.hiddenOwners.isEmpty() && it.offline.postCount == 0 }
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

    @Test fun `Codex preparation publishes coordinator progress and the ring state without opening a sheet`() = runBlocking {
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
            release.complete(Unit)
            val completed = owner.state.first { it.owners.singleOrNull()?.job?.isRunning == false && it.offline.postCount == 1 }
            assertEquals(1, completed.offline.postCount)
            assertEquals(1, completed.owners.single().job?.completedPosts)
            assertTrue(!completed.isOpen)
            assertEquals(CodexOfflineState(fraction = 1f), owner.state.first { "a" in it.codexStates }.codexStates["a"])
        }
    }

    @Test fun `the ring counts only the Codex's current posts`() = runBlocking<Unit> {
        withTimeout(10_000) {
            codices.ensureCodex("a", "First")
            val kept = post()
            val added = post("added")
            codices.addItems("a", listOf(kept))
            val coordinator = coordinator()
            coordinator.store.save(kept, kept.media, "codex:a") { _, file -> file.writeText("media") }
            val owner = owner(coordinator)
            owner.state.first { it.codexStates["a"] == CodexOfflineState(1f) }

            codices.addItems("a", listOf(added))
            owner.state.first { it.codexStates["a"] == CodexOfflineState(0.5f) }
            codices.removeItems("a", setOf(kept.id))
            owner.state.first { it.codexStates["a"] == CodexOfflineState(0f) }
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
            owner.state.first { it.owners.any(OfflineStorageOwner::preparing) }
            val undo = UndoSlot()
            owner.removeAll(undo)
            undo.expire(LIBRARY_TRANSFERS_UNDO_SCOPE)
            cancelled.await()
            owner.state.first { !it.working && it.hiddenOwners.isEmpty() }
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

    private fun post(id: String = "offline"): Post {
        val image = ImageRef(url = "https://example.test/$id.jpg", localPath = null, mime = "image/jpeg")
        return testPost(sourcePostId = id).copy(full = image, media = listOf(image), mediaCount = 1)
    }
}
