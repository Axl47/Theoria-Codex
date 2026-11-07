package com.theoriacodex.app.codex

import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
        val posts = listOf(testPost(canonicalTags = listOf("cat")))
        val query = emptySearchQuery()
        var lookups = 0
        val library = MutableStateFlow(mapOf("target" to listOf(testPost(sourcePostId = "saved", canonicalTags = listOf("cat")))))
        val loader = CodexSaveRelevanceLoader(repository, backgroundScope, StandardTestDispatcher(testScheduler)) { ids ->
            assertEquals(setOf("target"), ids)
            lookups++
            library
        }
        val menuScores = loader.observe(posts, query)
        val menu = backgroundScope.launch { menuScores.collect() }
        runCurrent()
        assertEquals(mapOf("target" to 1.0), menuScores.value)
        menu.cancel()
        advanceTimeBy(250)

        val pickerScores = loader.observe(posts, query)
        assertSame(menuScores, pickerScores)
        assertEquals(mapOf("target" to 1.0), pickerScores.value)
        val picker = backgroundScope.launch { pickerScores.collect() }
        runCurrent()
        assertEquals(1, lookups)
        library.value = mapOf("target" to listOf(testPost(sourcePostId = "saved", canonicalTags = listOf("dog"))))
        runCurrent()
        assertEquals(mapOf("target" to 0.0), pickerScores.value)

        picker.cancel()
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(0, library.subscriptionCount.value)
    }

    @Test fun `another post rescores the retained library instead of looking it up again`() = runTest {
        val repository = InMemoryCodexRepository()
        repository.ensureCodex("target", "Target")
        val saved = testPost(sourcePostId = "saved", canonicalTags = listOf("cat", "dog"))
        val library = MutableStateFlow(mapOf("target" to listOf(saved)))
        var lookups = 0
        val loader = CodexSaveRelevanceLoader(repository, backgroundScope, StandardTestDispatcher(testScheduler)) { _ ->
            lookups++
            library
        }
        val first = loader.observe(listOf(post("one", "cat")), null)
        backgroundScope.launch { first.collect() }
        runCurrent()
        assertEquals(mapOf("target" to 1.0), first.value)

        val second = loader.observe(listOf(post("two", "bird")), null)
        assertEquals(emptyMap<String, Double>(), second.value)
        backgroundScope.launch { second.collect() }
        runCurrent()
        assertEquals(mapOf("target" to 0.0), second.value)
        val withQuery = loader.observe(listOf(post("two", "bird")), emptySearchQuery())
        backgroundScope.launch { withQuery.collect() }
        runCurrent()

        assertEquals(1, lookups)
        assertEquals(1, library.subscriptionCount.value)
    }

    private fun post(id: String, tag: String): Post = testPost(sourcePostId = id, canonicalTags = listOf(tag))
}
