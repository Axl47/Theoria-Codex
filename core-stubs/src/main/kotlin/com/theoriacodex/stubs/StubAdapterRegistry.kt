package com.theoriacodex.stubs

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator

class StubAdapterRegistry(
    private val fixtureLoader: StubFixtureLoader = StubFixtureLoader(),
    val runtime: StubRuntime = StubRuntime(),
) : SourceAdapterRegistry {
    private val adaptersBySource: Map<SourceKey, SourceAdapter> = SourceKey.entries.associateWith { sourceKey ->
        JsonStubSourceAdapter(
            sourceKey = sourceKey,
            fixtureLoader = fixtureLoader,
            runtime = runtime,
        )
    }

    override fun availableSources(): Set<SourceKey> {
        return adaptersBySource.keys
    }

    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? {
        return adaptersBySource[sourceKey]
    }

    fun allAdapters(): List<SourceAdapter> {
        return adaptersBySource.values.toList()
    }

    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator {
        return UnifiedSearchOrchestrator(adaptersBySource)
    }
}
