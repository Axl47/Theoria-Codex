package com.theoriacodex.app.di

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlinx.coroutines.flow.StateFlow

internal class CompositeSourceAdapterRegistry(
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

    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator {
        val snapshot = custom.value
        cachedOrchestrator?.takeIf { (revision, _) -> revision == snapshot.revision }?.let { return it.second }
        return UnifiedSearchOrchestrator(builtInAdapters + snapshot.adapters, annotateListing = annotateListing)
            .also { cachedOrchestrator = snapshot.revision to it }
    }

    private fun availableBuiltInSources(): Set<SourceKey> =
        potentialBuiltIns.availableSources().intersect(availableBuiltIns.value)
}
