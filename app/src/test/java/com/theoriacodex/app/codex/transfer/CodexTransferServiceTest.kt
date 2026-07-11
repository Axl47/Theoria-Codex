package com.theoriacodex.app.codex.transfer

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.InMemoryCacheRepository
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlinx.coroutines.flow.first
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
        val exporter = CodexTransferService(source, cache, emptyRegistry())

        val exported = exporter.export(sourceCodex.codexId) as CodexExportResult.Success

        val target = InMemoryCodexRepository()
        val targetCache = InMemoryCacheRepository()
        val importer = CodexTransferService(target, targetCache, emptyRegistry())
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
        val service = CodexTransferService(repository, InMemoryCacheRepository(), emptyRegistry())

        assertTrue(service.export("missing") is CodexExportResult.NotFound)
        assertTrue(service.import(null, "target") is CodexImportResult.Unreadable)
        assertTrue(service.import("{}", "target") is CodexImportResult.Invalid)
    }

    private fun emptyRegistry(): SourceAdapterRegistry {
        val adapters = emptyMap<SourceKey, SourceAdapter>()
        return object : SourceAdapterRegistry {
            override fun availableSources(): Set<SourceKey> = emptySet()
            override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = null
            override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = UnifiedSearchOrchestrator(adapters)
        }
    }
}
