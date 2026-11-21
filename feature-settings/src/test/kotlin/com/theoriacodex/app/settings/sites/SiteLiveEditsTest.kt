package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.domain.source.SiteHeaders
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
    /** The sites list never catches up with a write here, as when it lags behind one. */
    private val saved = site()
    private val other = site(sourceId = "custom_gelbooru_com", label = "Gelbooru", host = "gelbooru.com")
    private val sites = mutableMapOf(saved.sourceId to saved, other.sourceId to other)
    private val writes = mutableListOf<CustomSiteEdit>()
    private val writtenSites = mutableListOf<String>()
    private var running = 0
    private var mostAtOnce = 0
    private var gate: CompletableDeferred<Unit>? = null

    private fun TestScope.edits(timers: CoroutineScope = backgroundScope) = SiteLiveEdits(timers, backgroundScope, sites::get) { sourceId, edit ->
        running++
        mostAtOnce = maxOf(mostAtOnce, running)
        gate?.await()
        writes += edit
        writtenSites += sourceId
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

    @Test fun `a site's last change flushed during a write is not replaced by another site's change`() = runTest {
        val edits = edits()
        gate = CompletableDeferred()
        edits.schedule(saved.sourceId, named("Safer"), Duration.ZERO)
        runCurrent()
        edits.schedule(saved.sourceId, named("Safest"), TYPING_SAVE_DELAY)
        edits.flush()
        edits.schedule(other.sourceId, LiveFields("Gel", other.includedInUnifiedAndForYou, other.headers), Duration.ZERO)
        runCurrent()
        gate?.complete(Unit)
        runCurrent()
        assertEquals(listOf(saved.sourceId, saved.sourceId, other.sourceId), writtenSites)
        assertEquals(listOf("Safer", "Safest", "Gel"), writes.map { it.label })
    }

    @Test fun `an agent saved elsewhere after a write is replaced when the user sets the first agent again`() = runTest {
        val edits = edits()
        val first = SiteHeaders(userAgent = "U1")
        edits.schedule(saved.sourceId, LiveFields(saved.label, saved.includedInUnifiedAndForYou, first), Duration.ZERO)
        runCurrent()
        sites[saved.sourceId] = saved.copy(headers = SiteHeaders(userAgent = "U2"))
        edits.schedule(saved.sourceId, LiveFields(saved.label, saved.includedInUnifiedAndForYou, first), Duration.ZERO)
        runCurrent()
        assertEquals(listOf(first, first), writes.map { it.headers })
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
