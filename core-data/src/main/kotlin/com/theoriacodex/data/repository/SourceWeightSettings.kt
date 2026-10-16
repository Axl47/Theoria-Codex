package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey

/** Keeps the active weights and durable per-combination memory in the same settings transaction. */
internal object SourceWeightSettings {
    fun normalize(runtime: SourceRuntimeSettings): SourceRuntimeSettings {
        val remembered = runtime.sourceWeightsByConfiguration
            .filter { (key, weights) -> key == configurationKey(weights.keys) }
            .mapValues { (_, weights) -> RepositoryPolicies.normalizeSourceWeights(weights.keys, weights) }
        val active = RepositoryPolicies.normalizeSourceWeights(runtime.enabledSources, runtime.sourceWeights)
        return runtime.copy(
            sourceWeights = active,
            sourceWeightsByConfiguration = remembered + (configurationKey(runtime.enabledSources) to active),
        )
    }

    fun selectSources(runtime: SourceRuntimeSettings, enabledSources: Set<SourceKey>): SourceRuntimeSettings {
        val current = normalize(runtime)
        return current.copy(
            enabledSources = enabledSources,
            sourceWeights = current.sourceWeightsByConfiguration[configurationKey(enabledSources)]
                ?: current.sourceWeights,
        )
    }

    private fun configurationKey(sources: Set<SourceKey>): String =
        sources.map(SourceKey::name).sorted().joinToString(",")
}
