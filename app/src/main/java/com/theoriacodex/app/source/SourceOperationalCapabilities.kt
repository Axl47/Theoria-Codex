package com.theoriacodex.app.source

import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.SourceKey

/**
 * Creator browsing is operational capability, not source metadata.
 *
 * Deriving it from the registered adapter interfaces keeps the UI honest when a provider gains or
 * loses support and avoids a second hard-coded provider allowlist.
 */
fun SourceAdapterRegistry.creatorBrowsingSources(): Set<SourceKey> {
    val available = availableSources()
    return SourcePresentationCatalog.orderedPresentations()
        .asSequence()
        .map(SourcePresentation::source)
        .filter { source -> source in available && adapterFor(source) is CreatorPostsSourceAdapter }
        .toCollection(linkedSetOf())
}
