package com.theoriacodex.domain.adapter

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator

interface SourceAdapterRegistry {
    fun availableSources(): Set<SourceKey>
    fun adapterFor(sourceKey: SourceKey): SourceAdapter?
    fun unifiedOrchestrator(): UnifiedSearchOrchestrator
}
