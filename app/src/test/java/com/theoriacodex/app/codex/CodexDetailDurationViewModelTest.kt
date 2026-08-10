package com.theoriacodex.app.codex

import com.theoriacodex.app.testing.TestAnimatedDurationEnricher
import com.theoriacodex.app.testing.animatedTestPost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodexDetailDurationViewModelTest {
    @Test
    fun `enrichment publishes duration-only copies for the active codex`() = runTest {
        val original = animatedTestPost(sourcePostId = "animated")
        val enricher = TestAnimatedDurationEnricher { 12_000L }
        val owner = CodexDetailDurationViewModel(enricher, coroutineScope = this)
        owner.synchronize("codex-a", listOf(original))

        owner.requestEnrichment("codex-a")
        advanceUntilIdle()

        assertEquals(listOf(original.id), enricher.requestedPostIds)
        assertEquals(original.copy(durationMs = 12_000L), owner.state.value.posts.single())
    }

    @Test
    fun `repository refresh preserves enriched duration but replacement codex drops it`() = runTest {
        val original = animatedTestPost(sourcePostId = "animated")
        val owner = CodexDetailDurationViewModel(
            animatedDurationEnricher = TestAnimatedDurationEnricher { 5_000L },
            coroutineScope = this,
        )
        owner.synchronize("codex-a", listOf(original))
        owner.requestEnrichment("codex-a")
        advanceUntilIdle()

        owner.synchronize("codex-a", listOf(original.copy(title = "repository refresh")))
        assertEquals(5_000L, owner.state.value.posts.single().durationMs)
        assertEquals("repository refresh", owner.state.value.posts.single().title)

        owner.synchronize("codex-b", listOf(original))
        assertEquals(null, owner.state.value.posts.single().durationMs)
    }

    @Test
    fun `request for a stale codex identity is ignored`() = runTest {
        val original = animatedTestPost(sourcePostId = "animated")
        val enricher = TestAnimatedDurationEnricher { 9_000L }
        val owner = CodexDetailDurationViewModel(enricher, coroutineScope = this)
        owner.synchronize("codex-b", listOf(original))

        owner.requestEnrichment("codex-a")
        advanceUntilIdle()

        assertEquals(emptyList<Any>(), enricher.requestedPostIds)
        assertEquals(null, owner.state.value.posts.single().durationMs)
    }
}
