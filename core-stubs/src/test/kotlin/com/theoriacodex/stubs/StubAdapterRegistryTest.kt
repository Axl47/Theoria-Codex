package com.theoriacodex.stubs

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.orchestration.SourceRunState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StubAdapterRegistryTest {
    @Test
    fun `unified orchestrator returns merged results from all sources`() = runBlocking {
        val registry = StubAdapterRegistry()
        registry.runtime.preset = StubScenarioPreset.NORMAL

        val result = registry.unifiedOrchestrator().search(
            query = sampleQuery(),
            enabledSources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.AIBOORU, SourceKey.NHENTAI),
            pageTokens = emptyMap(),
            weights = mapOf(
                SourceKey.PIXIV to 0.45,
                SourceKey.GELBOORU to 0.25,
                SourceKey.AIBOORU to 0.15,
                SourceKey.NHENTAI to 0.15,
            ),
        )

        assertTrue(result.items.isNotEmpty())
        assertEquals(4, result.statuses.count { it.state == SourceRunState.SUCCESS })
    }

    @Test
    fun `unified orchestrator reports partial failure scenario`() = runBlocking {
        val registry = StubAdapterRegistry()
        registry.runtime.preset = StubScenarioPreset.PARTIAL_FAILURE

        val result = registry.unifiedOrchestrator().search(
            query = sampleQuery(),
            enabledSources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.AIBOORU, SourceKey.NHENTAI),
            pageTokens = emptyMap(),
            weights = mapOf(
                SourceKey.PIXIV to 0.45,
                SourceKey.GELBOORU to 0.25,
                SourceKey.AIBOORU to 0.15,
                SourceKey.NHENTAI to 0.15,
            ),
        )

        val failed = result.statuses.first { it.source == SourceKey.GELBOORU }
        assertEquals(SourceRunState.FAILED, failed.state)
        assertEquals(SourceFailureReason.NETWORK, failed.failureReason)
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Unified,
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }
}
