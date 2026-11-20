package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TagCountCoordinatorTest {
    @Test fun `completed incremental counts survive timeout and remain cached`() = runTest {
        val delegate = TestAdapter(SourceKey.NHENTAI)
        var lookups = 0
        val adapter = object : SourceAdapter by delegate, TagCountLookupSourceAdapter {
            override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> = error("Use incremental lookup")
            override suspend fun fetchTagCountsIncrementally(tags: List<String>, onCounts: (Map<String, Int>) -> Unit) {
                lookups++
                delay(1_000)
                onCounts(mapOf("known" to 7))
                delay(10_000)
                onCounts(mapOf("slow" to 9))
            }
        }
        val store = RecordingTagStore()
        val owner = TagCountCoordinator(TestRegistry(listOf(adapter)), store, { _, tag -> tag },
            dispatcher = StandardTestDispatcher(testScheduler))
        val result = owner.fetch(SourceKey.NHENTAI, listOf("known", "slow"), emptyMap())

        assertEquals(4_000L, testScheduler.currentTime)
        assertEquals(mapOf("known" to 7, "slow" to null), result)
        assertEquals(7, store.getCount(SourceKey.NHENTAI, "known"))
        assertEquals(result, owner.fetch(SourceKey.NHENTAI, listOf("known", "slow"), emptyMap()))
        assertEquals(1, lookups)
    }

    @Test fun `unsupported count providers issue no autocomplete requests`() = runTest {
        for (source in listOf(SourceKey.PIXIV, SourceKey.IWARA)) {
            val adapter = TestAdapter(source)
            val owner = TagCountCoordinator(TestRegistry(listOf(adapter)), RecordingTagStore(), { _, tag -> tag },
                dispatcher = StandardTestDispatcher(testScheduler))
            assertEquals(mapOf("unknown" to null), owner.fetch(source, listOf("unknown"), emptyMap()))
            assertTrue(adapter.autocompletePrefixes.isEmpty())
        }
    }

    @Test fun `supported lookup is batched and missing decisions expire without repeating immediate requests`() = runTest {
        var calls = 0
        var now = 0L
        val delegate = TestAdapter(SourceKey.GELBOORU)
        val adapter = object : SourceAdapter by delegate, TagCountLookupSourceAdapter {
            override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> {
                calls++
                assertEquals(if (calls == 1) listOf("known", "missing") else listOf("missing"), tags)
                return mapOf("known" to 7).filterKeys { it in tags }
            }
        }
        val owner = TagCountCoordinator(TestRegistry(listOf(adapter)), RecordingTagStore(), { _, tag -> tag },
            clock = { now }, dispatcher = StandardTestDispatcher(testScheduler))
        val tags = listOf("known", "missing")
        assertEquals(mapOf("known" to 7, "missing" to null), owner.fetch(SourceKey.GELBOORU, tags, emptyMap()))
        owner.fetch(SourceKey.GELBOORU, tags, emptyMap())
        assertEquals(1, calls)
        now = 60_001L
        owner.fetch(SourceKey.GELBOORU, tags, emptyMap())
        assertEquals(2, calls)
    }

    @Test fun `overall timeout is bounded and parent cancellation remains cancellation`() = runTest {
        var calls = 0
        var cancelParent = false
        val delegate = TestAdapter(SourceKey.GELBOORU)
        val adapter = object : SourceAdapter by delegate, TagCountLookupSourceAdapter {
            override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> {
                calls++
                if (cancelParent) throw CancellationException("sheet departed")
                delay(20_000)
                return emptyMap()
            }
        }
        val owner = TagCountCoordinator(TestRegistry(listOf(adapter)), RecordingTagStore(), { _, tag -> tag },
            dispatcher = StandardTestDispatcher(testScheduler))
        val start = testScheduler.currentTime
        owner.fetch(SourceKey.GELBOORU, listOf("slow"), emptyMap())
        assertEquals(4_000L, testScheduler.currentTime - start)
        owner.fetch(SourceKey.GELBOORU, listOf("slow"), emptyMap())
        assertEquals(1, calls)
        cancelParent = true
        assertTrue(runCatching { owner.fetch(SourceKey.GELBOORU, listOf("new"), emptyMap()) }
            .exceptionOrNull() is CancellationException)
    }
}
