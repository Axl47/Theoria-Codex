package com.theoriacodex.app.codex

import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.InMemoryCodexRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodexSaveRelevanceLoaderTest {
    @Test fun `picker reuses hold menu lookup and its ready scores through the transition`() = runTest {
        val repository = InMemoryCodexRepository()
        repository.ensureCodex("target", "Target")
        val posts = listOf(testPost())
        val query = emptySearchQuery()
        var lookups = 0
        val updates = MutableStateFlow(mapOf("target" to 0.8))
        val loader = CodexSaveRelevanceLoader(repository, backgroundScope) { ids, incoming, applied ->
            assertEquals(setOf("target"), ids)
            assertEquals(posts, incoming)
            assertEquals(query, applied)
            lookups++
            updates
        }
        val menuScores = loader.observe(posts, query)
        val menu = backgroundScope.launch { menuScores.collect() }
        runCurrent()
        assertEquals(updates.value, menuScores.value)
        menu.cancel()
        advanceTimeBy(250)

        val pickerScores = loader.observe(posts, query)
        assertSame(menuScores, pickerScores)
        assertEquals(updates.value, pickerScores.value)
        val picker = backgroundScope.launch { pickerScores.collect() }
        runCurrent()
        assertEquals(1, lookups)
        updates.value = mapOf("target" to 0.9)
        runCurrent()
        assertEquals(updates.value, pickerScores.value)

        picker.cancel()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(0, updates.subscriptionCount.value)
    }

    @Test fun `changing post or applied query drops old scores and cancels old lookup`() = runTest {
        val repository = InMemoryCodexRepository()
        repository.ensureCodex("target", "Target")
        val updates = MutableStateFlow(mapOf("target" to 0.8))
        var lookups = 0
        val loader = CodexSaveRelevanceLoader(repository, backgroundScope) { _, _, _ ->
            lookups++
            updates
        }
        val first = loader.observe(listOf(testPost(sourcePostId = "one")), null)
        backgroundScope.launch { first.collect() }
        runCurrent()
        val posts = listOf(testPost(sourcePostId = "two"))
        val second = loader.observe(posts, null)
        assertEquals(emptyMap<String, Double>(), second.value)
        runCurrent()
        assertEquals(0, updates.subscriptionCount.value)
        backgroundScope.launch { second.collect() }
        runCurrent()
        val withQuery = loader.observe(posts, emptySearchQuery())
        assertEquals(emptyMap<String, Double>(), withQuery.value)
        backgroundScope.launch { withQuery.collect() }
        runCurrent()
        assertEquals(3, lookups)
        assertEquals(1, updates.subscriptionCount.value)
    }
}
