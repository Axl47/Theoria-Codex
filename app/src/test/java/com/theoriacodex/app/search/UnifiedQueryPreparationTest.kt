package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedQueryPreparationTest {
    @Test
    fun `successful compatibility mappings are reused and expire`() = runTest {
        val adapter = TestAdapter(SourceKey.GELBOORU).apply {
            autocomplete = listOf(TagSuggestion("blue_hair", "tag", 100))
        }
        var clock = 0L
        val preparation = UnifiedQueryPreparation { clock }
        val original = unifiedQuery("blue")
        assertEquals(listOf("blue_hair"), preparation.prepareGelbooru(adapter, original).includeTags)
        preparation.prepareGelbooru(adapter, original)
        assertEquals(1, adapter.autocompletePrefixes.size)
        clock += 31 * 60_000L
        preparation.prepareGelbooru(adapter, original)
        assertEquals(2, adapter.autocompletePrefixes.size)
    }

    @Test
    fun `timed out mappings fall back without poisoning the cache`() = runTest {
        val adapter = TestAdapter(SourceKey.GELBOORU).apply { autocompleteResponseDelayMs = 3_000L }
        val preparation = UnifiedQueryPreparation { testScheduler.currentTime }
        assertEquals(listOf("blue"), preparation.prepareGelbooru(adapter, unifiedQuery("blue")).includeTags)
        assertEquals(2_000L, testScheduler.currentTime)
        adapter.autocompleteResponseDelayMs = 0L
        adapter.autocomplete = listOf(TagSuggestion("blue_hair", "tag", 100))
        assertEquals(listOf("blue_hair"), preparation.prepareGelbooru(adapter, unifiedQuery("blue")).includeTags)
    }
}
