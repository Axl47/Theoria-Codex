package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import kotlin.math.floor
import kotlin.math.roundToInt

/** Keeps the active weights and durable per-combination memory in the same settings transaction. */
object SourceWeightSettings {
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

    /** Read the same combination-specific weights used by Search and its settings controls. */
    fun weightsFor(runtime: SourceRuntimeSettings, sources: Set<SourceKey>): Map<SourceKey, Double> =
        RepositoryPolicies.normalizeSourceWeights(
            sources,
            if (sources == runtime.enabledSources) runtime.sourceWeights
            else runtime.sourceWeightsByConfiguration[configurationKey(sources)] ?: runtime.sourceWeights,
        )

    /** Update one group without changing the sources used by other browsing surfaces. */
    fun updateWeights(
        runtime: SourceRuntimeSettings,
        sources: Set<SourceKey>,
        weights: Map<SourceKey, Double>,
    ): SourceRuntimeSettings {
        val normalized = RepositoryPolicies.normalizeSourceWeights(sources, weights)
        val current = normalize(runtime)
        return current.copy(
            sourceWeights = if (sources == current.enabledSources) normalized else current.sourceWeights,
            sourceWeightsByConfiguration = current.sourceWeightsByConfiguration +
                (configurationKey(sources) to normalized),
        )
    }

    /** Keep the edited source fixed and distribute the remaining 5% units proportionally. */
    fun redistribute(weights: Map<SourceKey, Double>, source: SourceKey, value: Double): Map<SourceKey, Double> {
        require(source in weights && weights.size > 1)
        val selectedUnits = (value.coerceIn(0.0, 1.0) * 20).roundToInt()
        val remaining = 20 - selectedUnits
        val others = weights.keys.filter { it != source }.sortedBy { it.name }
        val total = others.sumOf { weights.getValue(it) }
        val shares = others.associateWith {
            remaining * if (total > 0.0) weights.getValue(it) / total else 1.0 / others.size
        }
        val units = shares.mapValues { floor(it.value).toInt() }.toMutableMap()
        val unassigned = remaining - units.values.sum()
        others.sortedByDescending { shares.getValue(it) - units.getValue(it) }
            .take(unassigned).forEach { units[it] = units.getValue(it) + 1 }
        return weights.keys.associateWith {
            (if (it == source) selectedUnits else units.getValue(it)) / 20.0
        }
    }

    private fun configurationKey(sources: Set<SourceKey>): String =
        sources.map(SourceKey::name).sorted().joinToString(",")
}
