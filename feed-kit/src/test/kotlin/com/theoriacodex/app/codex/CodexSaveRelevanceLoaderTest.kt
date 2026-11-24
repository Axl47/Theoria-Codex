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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodexSaveRelevanceLoaderTest {
    @Test fun `picker reuses hold menu lookup and its ready scores through the transition`() = runTest {
        val repository = InMemoryCodexRepository()
        repository.ensureCodex("target", "Target")
        val posts = listOf(testPost(canonicalTags = listOf("cat")))
        val query = emptySearchQuery()
        var lookups = 0
        val library = MutableStateFlow(mapOf("target" to saved("cat")))
        val loader = CodexSaveRelevanceLoader(repository, backgroundScope, StandardTestDispatcher(testScheduler)) { ids ->
            assertEquals(setOf("target"), ids)
            lookups++
            library
        }
        val menu = backgroundScope.launch { loader.observe(posts, query).collect() }
        runCurrent()
        assertEquals(mapOf("target" to match), loader.latest(posts, query))
        menu.cancel()
        advanceTimeBy(250)

        assertEquals(mapOf("target" to match), loader.latest(posts, query))
        val pickerScores = mutableListOf<Map<String, Double>>()
        val picker = backgroundScope.launch { loader.observe(posts, query).collect { pickerScores += it } }
        runCurrent()
        assertEquals(1, lookups)
        library.value = mapOf("target" to saved("dog"))
        runCurrent()
        assertEquals(listOf(mapOf("target" to match), mapOf("target" to 0.0)), pickerScores)

        picker.cancel()
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(0, library.subscriptionCount.value)
        assertEquals(emptyMap<String, Double>(), loader.latest(posts, query))
    }

    @Test fun `concurrent requests for different posts all stay live on one library lookup`() = runTest {
        val repository = InMemoryCodexRepository()
        repository.ensureCodex("target", "Target")
        val library = MutableStateFlow(mapOf("target" to saved("cat")))
        var lookups = 0
        val loader = CodexSaveRelevanceLoader(repository, backgroundScope, StandardTestDispatcher(testScheduler)) { _ ->
            lookups++
            library
        }
        val cat = mutableListOf<Map<String, Double>>()
        val dog = mutableListOf<Map<String, Double>>()
        backgroundScope.launch { loader.observe(listOf(post("one", "cat")), null).collect { cat += it } }
        backgroundScope.launch { loader.observe(listOf(post("two", "dog")), null).collect { dog += it } }
        runCurrent()
        library.value = mapOf("target" to saved("dog"))
        runCurrent()

        assertEquals(listOf(mapOf("target" to match), mapOf("target" to 0.0)), cat)
        assertEquals(listOf(mapOf("target" to 0.0), mapOf("target" to match)), dog)
        assertEquals(1, lookups)
        assertEquals(1, library.subscriptionCount.value)
    }

    private fun post(id: String, tag: String): Post = testPost(sourcePostId = id, canonicalTags = listOf(tag))

    private fun saved(tag: String) = listOf(post("saved", tag), post("other", "bird"))

    private val match = saved("cat").let { library ->
        CodexSaveRelevance(listOf(post("one", "cat"))).score(library, TagRarity.of(library))
    }
}
