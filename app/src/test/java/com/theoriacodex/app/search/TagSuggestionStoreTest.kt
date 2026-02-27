package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSuggestionStoreTest {
    @Test
    fun `incoming metadata overrides weaker seeded entry`() {
        val tempDir = Files.createTempDirectory("tag-store-test").toFile()
        val store = FileBackedTagSuggestionStore(
            storeFile = tempDir.resolve("tag_suggestions.json"),
            seedData = mapOf(
                SourceKey.PIXIV to listOf(
                    TagSuggestion(text = "landscape", type = "seed", count = null),
                )
            ),
        )

        store.put(
            source = SourceKey.PIXIV,
            suggestions = listOf(
                TagSuggestion(text = "landscape", type = "tag", count = 120),
            ),
        )

        val saved = store.get(SourceKey.PIXIV, limit = 5)
        assertEquals("tag", saved.first().type)
        assertEquals(120, saved.first().count)
    }

    @Test
    fun `store size respects max entries per source`() {
        val tempDir = Files.createTempDirectory("tag-store-cap-test").toFile()
        val store = FileBackedTagSuggestionStore(
            storeFile = tempDir.resolve("tag_suggestions.json"),
            maxEntriesPerSource = 2,
        )

        store.put(
            source = SourceKey.GELBOORU,
            suggestions = listOf(
                TagSuggestion(text = "tag_one", type = "seen", count = null),
                TagSuggestion(text = "tag_two", type = "seen", count = null),
                TagSuggestion(text = "tag_three", type = "seen", count = null),
            ),
        )

        val saved = store.get(SourceKey.GELBOORU, limit = 10)
        assertEquals(2, saved.size)
        assertTrue(saved.map { it.text }.containsAll(listOf("tag_one", "tag_two")))
    }
}
