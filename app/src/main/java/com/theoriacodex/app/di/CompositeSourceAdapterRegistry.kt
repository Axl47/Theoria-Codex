package com.theoriacodex.app.di

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlinx.coroutines.flow.StateFlow

/**
 * Built-in sources plus user-added sites (plan C4). A built-in is available while [availableBuiltIns]
 * (the account store's capability state) allows it; custom sites are available while their snapshot
 * has an adapter. Capability changes keep every adapter and orchestrator instance. Every page any of them lists passes
 * [annotateListing] (picture hashes), whichever kind of source it came from.
 */
internal class CompositeSourceAdapterRegistry(
    /** Every exposed built-in, available or not; callers filter the orchestrator by their own source set. */
    private val potentialBuiltIns: SourceAdapterRegistry,
    private val availableBuiltIns: StateFlow<Set<SourceKey>>,
    private val custom: StateFlow<CustomSourceSnapshot>,
    private val annotateListing: suspend (List<Post>) -> List<Post> = { it },
) : SourceAdapterRegistry {
    private val builtInAdapters: Map<SourceKey, SourceAdapter> by lazy {
        potentialBuiltIns.availableSources().mapNotNull { source -> potentialBuiltIns.adapterFor(source)?.let { source to it } }.toMap()
    }

    @Volatile
    private var cachedOrchestrator: Pair<Long, UnifiedSearchOrchestrator>? = null

    override fun availableSources(): Set<SourceKey> = availableBuiltInSources() + custom.value.adapters.keys

    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? =
        sourceKey.takeIf { it in availableBuiltInSources() }?.let(potentialBuiltIns::adapterFor)
            ?: custom.value.adapters[sourceKey]

    /** One orchestrator per custom revision; callers fetch it per search, so changes apply to the next one. */
    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator {
        val snapshot = custom.value
        cachedOrchestrator?.takeIf { (revision, _) -> revision == snapshot.revision }?.let { return it.second }
        return UnifiedSearchOrchestrator(builtInAdapters + snapshot.adapters, annotateListing = annotateListing)
            .also { cachedOrchestrator = snapshot.revision to it }
    }

    /** The account store can name a source this build does not expose, so both must allow it. */
    private fun availableBuiltInSources(): Set<SourceKey> =
        potentialBuiltIns.availableSources().intersect(availableBuiltIns.value)
}
