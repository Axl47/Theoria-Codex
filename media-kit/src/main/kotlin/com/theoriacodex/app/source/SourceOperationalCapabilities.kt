package com.theoriacodex.app.source

import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.SourceKey

fun SourceAdapterRegistry.creatorBrowsingSources(): Set<SourceKey> {
    val available = availableSources()
    return SourcePresentationCatalog.orderedPresentations()
        .asSequence()
        .map(SourcePresentation::source)
        .filter { source -> source in available && adapterFor(source) is CreatorPostsSourceAdapter }
        .toCollection(linkedSetOf())
}
