package com.theoriacodex.app.di

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlinx.coroutines.flow.StateFlow

/**
 * Preserves adapter and orchestrator identity while account capabilities change.
 */
internal class AvailabilityAwareSourceAdapterRegistry(
    private val delegate: SourceAdapterRegistry,
    private val availableSourceState: StateFlow<Set<SourceKey>>,
) : SourceAdapterRegistry {
    override fun availableSources(): Set<SourceKey> {
        return delegate.availableSources().intersect(availableSourceState.value)
    }

    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? {
        if (sourceKey !in availableSources()) return null
        return delegate.adapterFor(sourceKey)
    }

    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator {
        // Callers pass the current effective source set to the orchestrator. The delegate can
        // therefore stay application-scoped even while this wrapper changes what is selectable.
        return delegate.unifiedOrchestrator()
    }
}
