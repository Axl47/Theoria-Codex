package com.theoriacodex.stubs

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator

class StubAdapterRegistry(
    private val fixtureLoader: StubFixtureLoader = StubFixtureLoader(),
    val runtime: StubRuntime = StubRuntime(),
) {
    private val adaptersBySource: Map<SourceKey, SourceAdapter> = SourceKey.entries.associateWith { sourceKey ->
        JsonStubSourceAdapter(
            sourceKey = sourceKey,
            fixtureLoader = fixtureLoader,
            runtime = runtime,
        )
    }

    fun adapterFor(sourceKey: SourceKey): SourceAdapter {
        return requireNotNull(adaptersBySource[sourceKey]) { "No adapter for $sourceKey" }
    }

    fun allAdapters(): List<SourceAdapter> {
        return adaptersBySource.values.toList()
    }

    fun unifiedOrchestrator(): UnifiedSearchOrchestrator {
        return UnifiedSearchOrchestrator(adaptersBySource)
    }
}
