package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceWeightSettingsTest {
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
