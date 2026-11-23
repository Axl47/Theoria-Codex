package com.theoriacodex.app.media

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.undo.PendingRemoval
import com.theoriacodex.app.ui.undo.UndoLifetime
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.data.repository.ChapterDownload
import com.theoriacodex.data.repository.DownloadScheduler
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.data.repository.SeriesDownloads
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.seriesOfflineOwner
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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

            owner.removeDeferred("codex:a")
            val hidden = owner.state.first { it.owners.map(OfflineStorageOwner::id) == listOf("codex:b") }
            assertEquals("Removed First", hidden.pendingRemovals["codex:a"]?.label)
            owner.actOnRemoval("codex:a")
            owner.state.first { it.owners.size == 2 }
            assertEquals(setOf("codex:a", "codex:b"), coordinator.store.snapshot.value.postsByOwner.keys)

            owner.removeDeferred("codex:a", UndoLifetime.UntilLeft)
            owner.commitRemovals()
            owner.state.first { it.hiddenOwners.isEmpty() && it.owners.map(OfflineStorageOwner::id) == listOf("codex:b") }
            assertEquals(setOf("codex:b"), coordinator.store.snapshot.value.postsByOwner.keys)
            assertNotNull(coordinator.find(post.id))

            owner.removeAll()
            owner.commitRemovals()
            owner.state.first { !it.working && it.hiddenOwners.isEmpty() && it.offline.postCount == 0 }
            assertNull(coordinator.find(post.id))
        }
    }

    @Test fun `a series' chapters are one row under its title, and removing it also drops its queued chapters`() = runBlocking {
        withTimeout(10_000) {
            val series = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
            val coordinator = coordinator()
            val chapter = post("chapter")
            coordinator.store.save(chapter, chapter.media, seriesOfflineOwner(series)) { _, file -> file.writeText("media") }
            val repository = seriesRepository(StoredSeries(Series(series, "Title")))
            val downloads = SeriesDownloads(
                coordinator.store, temporary.root.resolve("queue.json"), repository, sources = { emptyList() },
                fetch = { _, _ -> error("unused") }, scheduler = NoScheduler, deleteAfterReading = { false },
            )
            downloads.download(series, listOf("/queued"))
            val owner = OfflineStorageViewModel(
                codices, coordinator, readDisposableUsage = { DisposableMediaCacheSnapshot() }, clearDisposable = { true },
                removalScope = scope, seriesRepository = repository, seriesDownloads = downloads, scopeOverride = scope,
            )
            val row = owner.state.first { it.owners.singleOrNull()?.name == "Title" }.owners.single()
            assertEquals("1 chapter offline", offlineOwnerStatus(row))

            owner.removeDeferred(row.id, UndoLifetime.UntilLeft)
            owner.commitRemovals()
            owner.state.first { !it.working && it.hiddenOwners.isEmpty() && it.owners.isEmpty() }
            assertNull(coordinator.find(chapter.id))
            assertEquals(emptyMap<String, ChapterDownload>(), downloads.states(series).first())
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
                removalScope = scope, scopeOverride = scope,
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

    @Test fun `removing a Codex's copy until left hides its ring and holds its Undo by Codex id until committed`() = runBlocking {
        withTimeout(10_000) {
            codices.ensureCodex("a", "First")
            val kept = post()
            codices.addItems("a", listOf(kept))
            val coordinator = coordinator()
            coordinator.store.save(kept, kept.media, "codex:a") { _, file -> file.writeText("media") }
            val owner = owner(coordinator)
            owner.state.first { it.codexStates["a"] == CodexOfflineState(1f) }

            owner.removeDeferred(codexOfflineOwner("a"), UndoLifetime.UntilLeft)
            val pending = owner.state.first { "a" !in it.codexStates }
            assertEquals(PendingRemoval.Status.Waiting, pending.codexRemovals["a"]?.status)
            owner.actOnRemoval(codexOfflineOwner("a"))
            owner.state.first { it.codexStates["a"] == CodexOfflineState(1f) && it.codexRemovals.isEmpty() }

            owner.removeDeferred(codexOfflineOwner("a"), UndoLifetime.UntilLeft)
            owner.state.first { "a" in it.codexRemovals }
            assertEquals(setOf("codex:a"), coordinator.store.snapshot.value.postsByOwner.keys)
            owner.commitRemovals()
            val committed = owner.state.first { it.codexRemovals.isEmpty() && it.offline.postCount == 0 }
            assertNull(committed.codexStates["a"])
        }
    }

    @Test fun `Remove all takes over a waiting single removal so one Undo puts every copy back`() = runBlocking {
        withTimeout(10_000) {
            codices.ensureCodex("a", "First")
            codices.ensureCodex("b", "Second")
            val coordinator = coordinator()
            val first = post("first")
            val second = post("second")
            coordinator.store.save(first, first.media, "codex:a") { _, file -> file.writeText("media") }
            coordinator.store.save(second, second.media, "codex:b") { _, file -> file.writeText("media") }
            val owner = owner(coordinator)
            owner.state.first { it.owners.size == 2 }

            owner.removeDeferred("codex:a", UndoLifetime.UntilLeft)
            owner.state.first { it.owners.size == 1 }
            owner.removeAll()
            assertEquals(setOf(OFFLINE_ALL_REMOVAL), owner.state.first { it.owners.isEmpty() }.pendingRemovals.keys)
            owner.actOnRemoval(OFFLINE_ALL_REMOVAL)
            val restored = owner.state.first { it.owners.size == 2 }
            assertTrue(restored.pendingRemovals.isEmpty())
            assertEquals(setOf("codex:a", "codex:b"), coordinator.store.snapshot.value.postsByOwner.keys)
        }
    }

    @Test fun `making an empty Codex available offline reports why it could not start`() = runBlocking {
        withTimeout(10_000) {
            codices.ensureCodex("a", "First")
            val owner = owner(coordinator())
            owner.makeAvailableOffline("a")
            assertEquals("This Codex has no posts to keep offline.", owner.preparationFailures.first())
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
            owner.removeAll()
            owner.commitRemovals()
            cancelled.await()
            owner.state.first { !it.working && it.hiddenOwners.isEmpty() }
            release.complete(Unit)
            assertTrue(coordinator.jobs.value.isEmpty())
            assertTrue(owner.state.value.preparingOwners.isEmpty())
        }
    }

    @Test fun `deleting a Codex while its offline preparation loads never starts a job for it`() = runBlocking {
        withTimeout(10_000) {
            codices.ensureCodex("a", "First")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = object : CodexRepository by codices {
                override fun observeCodexPosts(codexId: String, sort: CodexSortMode) = flow {
                    started.complete(Unit)
                    release.await()
                    emit(listOf(post()))
                }
            }
            val coordinator = coordinator()
            val owner = owner(coordinator, repository)
            owner.makeAvailableOffline("a")
            started.await()

            // The Codex delete's commit removes the owner straight through the coordinator.
            coordinator.remove(codexOfflineOwner("a"))
            release.complete(Unit)
            owner.state.first { it.preparingOwners.isEmpty() }
            assertTrue(coordinator.jobs.value.isEmpty())
        }
    }

    private fun coordinator() = OfflineMediaCoordinator(
        store = OfflineMediaStore(temporary.newFolder()), scope = scope,
        resolve = { it }, selectMedia = { it.media }, acquire = { _, _, output -> output.writeText("media") },
    )

    private fun owner(coordinator: OfflineMediaCoordinator, repository: CodexRepository = codices) = OfflineStorageViewModel(
        repository, coordinator, readDisposableUsage = { DisposableMediaCacheSnapshot() },
        clearDisposable = { true }, removalScope = scope, scopeOverride = scope,
    )

    /** A repository that only observes [stored]; nothing else here asks it anything. */
    private fun seriesRepository(stored: StoredSeries) = Proxy.newProxyInstance(
        SeriesRepository::class.java.classLoader, arrayOf(SeriesRepository::class.java),
    ) { _, method, _ -> if (method.name == "observeSeries") flowOf(stored) else error("unused: ${method.name}") } as SeriesRepository

    private object NoScheduler : DownloadScheduler {
        override suspend fun schedule(automatic: Boolean) = Unit
        override suspend fun ensureScheduled(automatic: Boolean) = Unit
    }

    private fun post(id: String = "offline"): Post {
        val image = ImageRef(url = "https://example.test/$id.jpg", localPath = null, mime = "image/jpeg")
        return testPost(sourcePostId = id).copy(full = image, media = listOf(image), mediaCount = 1)
    }
}
