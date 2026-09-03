package com.theoriacodex.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerTranslationCacheRepositoryTest {
    @Test
    fun `entries expire and least recently used entries are evicted`() = runTest {
        var now = 100L
        val repository = InMemoryViewerTranslationCacheRepository(
            maxEntries = 2,
            ttlMs = 50L,
            clock = { now },
        )
        val first = key("first")
        val second = key("second")
        val third = key("third")
        repository.putAll(mapOf(first to "one", second to "two"))
        now += 1L
        repository.getAll(setOf(first))
        now += 1L
        repository.putAll(mapOf(third to "three"))

        val retained = repository.getAll(setOf(first, second, third))

        assertEquals(mapOf(first to "one", third to "three"), retained)
        now = 153L
        assertTrue(repository.getAll(setOf(first, third)).isEmpty())
    }

    private fun key(text: String) = ViewerTranslationCacheKey(
        backendVersion = "google-nmt-v1",
        sourceLanguage = ViewerOcrLanguage.JAPANESE,
        sourceText = text,
    )
}
