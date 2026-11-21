package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.customsites.CustomSiteEdit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class SiteLiveEditsTest {
    /** The sites list never catches up here, as when it lags behind a write. */
    private val saved = site()
    private val writes = mutableListOf<CustomSiteEdit>()
    private var running = 0
    private var mostAtOnce = 0
    private var gate: CompletableDeferred<Unit>? = null

    private fun TestScope.edits(timers: CoroutineScope = backgroundScope) = SiteLiveEdits(timers, backgroundScope, { saved }) { _, edit ->
        running++
        mostAtOnce = maxOf(mostAtOnce, running)
        gate?.await()
        writes += edit
        running--
        true
    }

    private fun named(label: String) = LiveFields(label, saved.includedInUnifiedAndForYou, saved.headers)

    @Test fun `a revert made while a write runs is written after it, one write at a time`() = runTest {
        val edits = edits()
        gate = CompletableDeferred()
        edits.schedule(saved.sourceId, named("Safer"), Duration.ZERO)
        runCurrent()
        edits.schedule(saved.sourceId, named(saved.label), Duration.ZERO)
        runCurrent()
        gate?.complete(Unit)
        runCurrent()
        assertEquals(listOf(CustomSiteEdit(label = "Safer"), CustomSiteEdit(label = saved.label)), writes)
        assertEquals(1, mostAtOnce)
    }

    @Test fun `a write running and the last change survive the owner's scope ending`() = runTest {
        val ownerScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val edits = edits(timers = ownerScope)
        gate = CompletableDeferred()
        edits.schedule(saved.sourceId, named("Safer"), Duration.ZERO)
        runCurrent()
        edits.schedule(saved.sourceId, named("Safest"), TYPING_SAVE_DELAY)
        ownerScope.cancel()
        edits.flush()
        gate?.complete(Unit)
        runCurrent()
        assertEquals(listOf(CustomSiteEdit(label = "Safer"), CustomSiteEdit(label = "Safest")), writes)
    }

    @Test fun `a change back to the saved value writes nothing`() = runTest {
        val edits = edits()
        edits.schedule(saved.sourceId, named("Safer"), TYPING_SAVE_DELAY)
        edits.schedule(saved.sourceId, named(saved.label), TYPING_SAVE_DELAY)
        edits.flush()
        runCurrent()
        assertEquals(emptyList<CustomSiteEdit>(), writes)
    }
}
