package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceWeightSettingsTest {
    @Test
    fun `every slider step preserves edited weight and distributes whole steps totaling one`() {
        for (count in 2..SourceKey.entries.size) {
            val sources = SourceKey.entries.take(count).toSet()
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
    fun `group weights survive normalization without changing other source configurations`() {
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
    fun `editing active configuration updates active weights too`() {
        val group = setOf(SourceKey.PIXIV, SourceKey.GELBOORU)
        val updated = SourceWeightSettings.normalize(SourceWeightSettings.updateWeights(
            SourceRuntimeSettings(enabledSources = group),
            group, mapOf(SourceKey.PIXIV to 0.2, SourceKey.GELBOORU to 0.8),
        ))
        assertEquals(0.8, updated.sourceWeights.getValue(SourceKey.GELBOORU), 0.00001)
        assertEquals(updated.sourceWeights, SourceWeightSettings.weightsFor(updated, group))
    }
}
