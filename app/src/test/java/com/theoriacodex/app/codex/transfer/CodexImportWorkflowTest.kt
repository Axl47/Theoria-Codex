package com.theoriacodex.app.codex.transfer

import com.google.gson.Gson
import com.theoriacodex.app.codex.CodexShareFile
import com.theoriacodex.app.codex.CodexSharePost
import com.theoriacodex.app.codex.buildCodexShareFile
import com.theoriacodex.app.testing.InMemoryCodexLikesTransactions
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.InMemoryCacheRepository
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.stubs.StubAdapterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexImportWorkflowTest {
    @Test
    fun `complete snapshots import without entering a provider and invalid documents never commit`() = runTest {
        val repository = InMemoryCodexRepository()
        var calls = 0
        val service = service(repository, StandardTestDispatcher(testScheduler)) { calls++; awaitCancellation() }
        val raw = Gson().toJson(buildCodexShareFile("Saved", listOf(testPost())))
        assertTrue(service.import(raw, "saved") is CodexImportResult.Success)
        assertEquals(0, calls)
        for (invalid in listOf(raw.replace("\"version\":2", "\"version\":3"), "[".repeat(65) + "]".repeat(65))) {
            assertEquals(CodexImportResult.Invalid, service.import(invalid, "invalid"))
            assertNull(repository.observeCodex("invalid").first())
        }
    }

    @Test
    fun `legacy resolutions are bounded concurrent ordered and report progress`() = runTest {
        val repository = InMemoryCodexRepository()
        var active = 0
        var peak = 0
        val progress = mutableListOf<CodexImportProgress>()
        val service = service(repository, StandardTestDispatcher(testScheduler)) { id ->
            active++
            peak = maxOf(peak, active)
            try { delay(1_000); testPost(sourcePostId = id.sourcePostId) } finally { active-- }
        }
        assertEquals(CodexImportResult.Success("saved", 17, 0), service.import(legacy(17), "saved", progress::add))
        assertEquals(4, peak)
        assertEquals(5_000L, testScheduler.currentTime)
        assertEquals((0..17).toList() + 17, progress.map { it.completed })
        assertTrue(progress.last().committing)
        assertEquals(17, repository.observeCodexItems("saved").first().size)
    }

    @Test
    fun `cancelling a retained import stops requests and retry retains its captured target`() = runTest {
        val repository = InMemoryCodexRepository()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var targets = 0
        var reads = 0
        val service = service(repository, StandardTestDispatcher(testScheduler)) { id ->
            active++
            try { release.await(); testPost(sourcePostId = id.sourcePostId) } finally { active-- }
        }
        val owner = CodexImportViewModel(service, { reads++; legacy(8) }, { "profile-${++targets}" }, this)
        owner.start("document")
        runCurrent()
        assertEquals(4, active)
        assertTrue(owner.state.value.running)
        owner.start("duplicate while detached")
        assertEquals(1, reads)
        assertNull(repository.observeCodex("profile-1").first())
        owner.cancel()
        runCurrent()
        assertEquals(0, active)
        assertFalse(owner.state.value.running)
        assertNull(repository.observeCodex("profile-1").first())
        owner.retry()
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()
        // The UI attaches only after completion and still receives the typed result once.
        assertEquals(CodexImportResult.Success("profile-1", 8, 0), owner.effects.first())
        assertEquals(1, targets)
        assertEquals(2, reads)
    }

    @Test
    fun `overall legacy timeout and external cancellation leave no partial collection`() = runTest {
        val repository = InMemoryCodexRepository()
        var active = 0
        val service = service(repository, StandardTestDispatcher(testScheduler)) {
            active++
            try { awaitCancellation() } finally { active-- }
        }
        assertEquals(CodexImportResult.TimedOut, service.import(legacy(100), "timeout"))
        assertEquals(120_000L, testScheduler.currentTime)
        assertEquals(0, active)
        assertNull(repository.observeCodex("timeout").first())
        val importing = async { service.import(legacy(8), "cancelled") }
        runCurrent()
        importing.cancelAndJoin()
        assertEquals(0, active)
        assertNull(repository.observeCodex("cancelled").first())
    }

    private fun legacy(count: Int) = Gson().toJson(CodexShareFile(1, "Legacy", (1..count).map { CodexSharePost("PIXIV", "$it") }))

    private fun service(
        repository: InMemoryCodexRepository,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        resolve: suspend (PostId) -> Post?,
    ): CodexTransferService {
        val stub = StubAdapterRegistry()
        val adapter = object : SourceAdapter by requireNotNull(stub.adapterFor(SourceKey.PIXIV)) {
            override suspend fun resolvePost(id: PostId) = resolve(id)
        }
        val registry = object : SourceAdapterRegistry by stub {
            override fun adapterFor(sourceKey: SourceKey) = adapter
        }
        return CodexTransferService(repository, InMemoryCodexLikesTransactions(codices = repository),
            InMemoryCacheRepository(), registry, workerDispatcher = dispatcher)
    }
}
