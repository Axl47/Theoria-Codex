package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceWeightSettingsTest {
    @Test
    fun `every slider step preserves edited weight and distributes whole steps totaling one`() {
        for (count in 2..SourceKey.builtIns.size) {
            val sources = SourceKey.builtIns.take(count).toSet()
            val original = SourceWeightSettings.weightsFor(SourceRuntimeSettings(), sources)
            val source = sources.first()
            for (step in 0..20) {
                val updated = SourceWeightSettings.redistribute(original, source, step / 20.0)
                assertEquals(step / 20.0, updated.getValue(source), 0.000001)
                assertEquals(1.0, updated.values.sum(), 0.000001)
                updated.values.forEach { assertEquals(kotlin.math.round(it * 20), it * 20, 0.000001) }
                val saved = SourceWeightSettings.updateWeights(SourceRuntimeSettings(), sources, updated)
                assertEquals(step / 20.0, SourceWeightSettings.weightsFor(saved, sources).getValue(source), 0.000001)
            }
        }
    }

    @Test
    fun `moving away from full weight redistributes across zero weight peers`() {
        val initial = mapOf(SourceKey.PIXIV to 1.0, SourceKey.GELBOORU to 0.0, SourceKey.IWARA to 0.0)
        val updated = SourceWeightSettings.redistribute(initial, SourceKey.PIXIV, 0.5)
        assertEquals(mapOf(SourceKey.PIXIV to 0.5, SourceKey.GELBOORU to 0.25, SourceKey.IWARA to 0.25), updated)
    }

    @Test
    fun `group weights survive normalization without changing other source configurations (ADR 0036)`() {
        val group = setOf(SourceKey.PIXIV, SourceKey.GELBOORU)
        val original = SourceWeightSettings.normalize(SourceRuntimeSettings())
        val updated = SourceWeightSettings.normalize(SourceWeightSettings.updateWeights(
            original, group, mapOf(SourceKey.PIXIV to 0.8, SourceKey.GELBOORU to 0.2),
        ))

        assertEquals(original.enabledSources, updated.enabledSources)
        assertEquals(original.sourceWeights, updated.sourceWeights)
        assertEquals(0.8, SourceWeightSettings.weightsFor(updated, group).getValue(SourceKey.PIXIV), 0.00001)
        assertEquals(group, SourceWeightSettings.weightsFor(updated, group).keys)
    }

    @Test
    fun `editing active configuration updates active weights too (ADR 0036)`() {
        val group = setOf(SourceKey.PIXIV, SourceKey.GELBOORU)
        val updated = SourceWeightSettings.normalize(SourceWeightSettings.updateWeights(
            SourceRuntimeSettings(enabledSources = group),
            group, mapOf(SourceKey.PIXIV to 0.2, SourceKey.GELBOORU to 0.8),
        ))
        assertEquals(0.8, updated.sourceWeights.getValue(SourceKey.GELBOORU), 0.00001)
        assertEquals(updated.sourceWeights, SourceWeightSettings.weightsFor(updated, group))
    }

    @Test
    fun `a newly opted-in custom source gets an even share instead of dominating (ADR 0029)`() {
        val custom = requireNotNull(SourceKey.parse("custom_safebooru_org"))
        val existing = mapOf(SourceKey.PIXIV to 0.5, SourceKey.GELBOORU to 0.5)

        val weights = RepositoryPolicies.normalizeSourceWeights(setOf(SourceKey.PIXIV, SourceKey.GELBOORU, custom), existing)

        listOf(SourceKey.PIXIV, SourceKey.GELBOORU, custom).forEach { source ->
            assertEquals(1.0 / 3.0, weights.getValue(source), 1e-9)
        }
        assertEquals(listOf(SourceKey.PIXIV, SourceKey.GELBOORU, custom), weights.keys.toList())
    }

    @Test
    fun `weights already normalized pass through bit for bit (ADR 0029)`() {
        val weights = mapOf(SourceKey.PIXIV to 0.3, SourceKey.GELBOORU to 0.3, SourceKey.IWARA to 0.4)

        assertEquals(weights, RepositoryPolicies.normalizeSourceWeights(weights.keys, weights))
    }

    @Test
    fun `several largest weights normalize to finite shares summing to one (ADR 0029)`() {
        val weights = mapOf(SourceKey.PIXIV to Double.MAX_VALUE, SourceKey.GELBOORU to Double.MAX_VALUE, SourceKey.IWARA to 0.0)

        assertEquals(mapOf(SourceKey.PIXIV to 0.5, SourceKey.GELBOORU to 0.5, SourceKey.IWARA to 0.0),
            RepositoryPolicies.normalizeSourceWeights(weights.keys, weights))
    }

    @Test
    fun `a missing weight whose mean overflows falls back to even shares (ADR 0029)`() {
        val custom = requireNotNull(SourceKey.parse("custom_safebooru_org"))
        val weights = mapOf(SourceKey.PIXIV to Double.MAX_VALUE, SourceKey.GELBOORU to Double.MAX_VALUE)

        val normalized = RepositoryPolicies.normalizeSourceWeights(weights.keys + custom, weights)

        assertEquals(listOf(1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0), normalized.values.toList())
    }
}
