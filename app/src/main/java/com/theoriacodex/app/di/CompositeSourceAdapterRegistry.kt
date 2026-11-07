package com.theoriacodex.app.di

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlinx.coroutines.flow.StateFlow

/**
 * Built-in sources plus user-added sites (plan C4). Custom adapters sit outside [builtIn] because
 * the availability wrapper intersects with the account store's built-in set and would hide them.
 */
internal class CompositeSourceAdapterRegistry(
    /** Availability-aware built-ins. */
    private val builtIn: SourceAdapterRegistry,
    /** Every exposed built-in, available or not; callers filter the orchestrator by their own source set. */
    private val potentialBuiltIns: SourceAdapterRegistry,
    private val custom: StateFlow<CustomSourceSnapshot>,
) : SourceAdapterRegistry {
    private val builtInAdapters: Map<SourceKey, SourceAdapter> by lazy {
        potentialBuiltIns.availableSources().mapNotNull { source -> potentialBuiltIns.adapterFor(source)?.let { source to it } }.toMap()
    }

    @Volatile
    private var cachedOrchestrator: Pair<Long, UnifiedSearchOrchestrator>? = null

    override fun availableSources(): Set<SourceKey> = builtIn.availableSources() + custom.value.adapters.keys

    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? =
        builtIn.adapterFor(sourceKey) ?: custom.value.adapters[sourceKey]

    /** One orchestrator per custom revision; callers fetch it per search, so changes apply to the next one. */
    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator {
        val snapshot = custom.value
        if (snapshot.adapters.isEmpty()) return potentialBuiltIns.unifiedOrchestrator()
        cachedOrchestrator?.takeIf { (revision, _) -> revision == snapshot.revision }?.let { return it.second }
        return UnifiedSearchOrchestrator(builtInAdapters + snapshot.adapters).also { cachedOrchestrator = snapshot.revision to it }
    }
}
