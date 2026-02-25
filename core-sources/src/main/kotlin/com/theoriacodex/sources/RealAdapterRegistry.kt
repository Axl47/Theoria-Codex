package com.theoriacodex.sources

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.sources.aibooru.AibooruSourceAdapter
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.gelbooru.GelbooruSourceAdapter
import com.theoriacodex.sources.http.DefaultSourceHttpClient
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.pixiv.PixivSourceAdapter

class RealAdapterRegistry(
    credentialsProvider: SourceCredentialsProvider,
    httpClient: SourceHttpClient = DefaultSourceHttpClient(),
    exposedSources: Set<SourceKey> = setOf(SourceKey.PIXIV),
) : SourceAdapterRegistry {
    private val adaptersBySource: Map<SourceKey, SourceAdapter> = mapOf(
        SourceKey.PIXIV to PixivSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentialsProvider,
        ),
        SourceKey.AIBOORU to AibooruSourceAdapter(httpClient = httpClient),
        SourceKey.GELBOORU to GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentialsProvider,
        ),
    )

    private val exposed = exposedSources.filter { it in adaptersBySource }.toSet()
    private val orchestrator by lazy {
        UnifiedSearchOrchestrator(adaptersBySource.filterKeys { it in exposed })
    }

    override fun availableSources(): Set<SourceKey> {
        return exposed
    }

    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? {
        if (sourceKey !in exposed) return null
        return adaptersBySource[sourceKey]
    }

    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator {
        return orchestrator
    }
}
