package com.theoriacodex.stubs

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.orchestration.SourceRunState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StubAdapterRegistryTest {
    @Test
    fun `unified orchestrator returns merged results from all sources`() = runTest {
        val registry = StubAdapterRegistry()
        registry.runtime.preset = StubScenarioPreset.NORMAL
        val enabledSources = SourceKey.entries.toSet()
        val equalWeight = 1.0 / enabledSources.size.toDouble()

        val result = registry.unifiedOrchestrator().search(
            query = sampleQuery(),
            enabledSources = enabledSources,
            pageTokens = emptyMap(),
            weights = enabledSources.associateWith { equalWeight },
        )

        assertTrue(result.items.isNotEmpty())
        assertEquals(SourceKey.entries.size, result.statuses.count { it.state == SourceRunState.SUCCESS })
    }

    @Test
    fun `unified orchestrator reports partial failure scenario`() = runTest {
        val registry = StubAdapterRegistry()
        registry.runtime.preset = StubScenarioPreset.PARTIAL_FAILURE
        val enabledSources = SourceKey.entries.toSet()
        val equalWeight = 1.0 / enabledSources.size.toDouble()

        val result = registry.unifiedOrchestrator().search(
            query = sampleQuery(),
            enabledSources = enabledSources,
            pageTokens = emptyMap(),
            weights = enabledSources.associateWith { equalWeight },
        )

        val failed = result.statuses.first { it.source == SourceKey.GELBOORU }
        assertEquals(SourceRunState.FAILED, failed.state)
        assertEquals(SourceFailureReason.NETWORK, failed.failureReason)
        assertEquals(SourceKey.entries.size - 1, result.statuses.count { it.state == SourceRunState.SUCCESS })
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
