package com.theoriacodex.app.codex.transfer

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.testing.InMemoryCodexLikesTransactions
import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.CacheSnapshot
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.InMemoryCacheRepository
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexTransferServiceTest {
    @Test
    fun `export and import round trip snapshots without a live provider`() = runTest {
        val source = InMemoryCodexRepository()
        val cache = InMemoryCacheRepository()
        val sourceCodex = source.ensureCodex("source", "My Codex")
        val first = testPost(sourcePostId = "one")
        val second = testPost(sourcePostId = "two")
        source.addItem(sourceCodex.codexId, first)
        source.addItem(sourceCodex.codexId, second)
        val exporter = CodexTransferService(
            source,
            InMemoryCodexLikesTransactions(codices = source),
            cache,
            emptyRegistry(),
        )

        val exported = exporter.export(sourceCodex.codexId) as CodexExportResult.Success

        val target = InMemoryCodexRepository()
        val targetCache = InMemoryCacheRepository()
        val importer = CodexTransferService(
            target,
            InMemoryCodexLikesTransactions(codices = target),
            targetCache,
            emptyRegistry(),
        )
        val imported = importer.import(exported.payload.json, "target") as CodexImportResult.Success

        assertEquals(2, imported.imported)
        assertEquals(0, imported.skipped)
        assertEquals(
            setOf(first.id, second.id),
            target.observeCodexPosts("target", CodexSortMode.NEWEST_SAVED).first().map { it.id }.toSet(),
        )
        assertEquals(2, targetCache.observeSnapshot().first().thumbnailCount)
        assertEquals("my_codex.json", exported.payload.fileName)
    }

    @Test
    fun `invalid and missing exports remain typed outcomes`() = runTest {
        val repository = InMemoryCodexRepository()
        val service = CodexTransferService(
            repository,
            InMemoryCodexLikesTransactions(codices = repository),
            InMemoryCacheRepository(),
            emptyRegistry(),
        )

        assertTrue(service.export("missing") is CodexExportResult.NotFound)
        assertTrue(service.import(null, "target") is CodexImportResult.Unreadable)
        assertTrue(service.import("{}", "target") is CodexImportResult.Invalid)
    }

    @Test
    fun `cache failure after commit does not report a durable import as failed`() = runTest {
        val source = InMemoryCodexRepository()
        val sourceCodex = source.ensureCodex("source", "Durable")
        val post = testPost(sourcePostId = "durable")
        source.addItem(sourceCodex.codexId, post)
        val exported = CodexTransferService(
            source,
            InMemoryCodexLikesTransactions(codices = source),
            InMemoryCacheRepository(),
            emptyRegistry(),
        ).export(sourceCodex.codexId) as CodexExportResult.Success

        val target = InMemoryCodexRepository()
        val imported = CodexTransferService(
            target,
            InMemoryCodexLikesTransactions(codices = target),
            failingThumbnailCache(),
            emptyRegistry(),
        ).import(exported.payload.json, "target") as CodexImportResult.Success

        assertEquals(1, imported.imported)
        assertEquals(
            listOf(post.id),
            target.observeCodexPosts("target", CodexSortMode.NEWEST_SAVED).first().map(Post::id),
        )
    }

    private fun emptyRegistry(): SourceAdapterRegistry {
        val adapters = emptyMap<SourceKey, SourceAdapter>()
        return object : SourceAdapterRegistry {
            override fun availableSources(): Set<SourceKey> = emptySet()
            override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = null
            override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = UnifiedSearchOrchestrator(adapters)
        }
    }

    private fun failingThumbnailCache(): CacheRepository = object : CacheRepository {
        override fun observeSnapshot(): Flow<CacheSnapshot> = flowOf(CacheSnapshot(0, 0))
        override suspend fun cacheThumbnail(post: Post) = error("cache unavailable")
        override suspend fun cacheFull(post: Post) = Unit
        override suspend fun clearThumbnailCache() = Unit
        override suspend fun clearFullImageCache() = Unit
    }
}
