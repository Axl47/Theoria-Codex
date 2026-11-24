package com.theoriacodex.app.settings.sites

import com.theoriacodex.app.customsites.CustomSiteEdit
import com.theoriacodex.app.customsites.HeaderEdit
import com.theoriacodex.domain.source.SiteHeaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class SiteLiveEditsTest {
    private val saved = site()
    private val other = site(sourceId = "custom_gelbooru_com", label = "Gelbooru", host = "gelbooru.com")
    private val sites = mutableMapOf(saved.sourceId to saved, other.sourceId to other)
    private val writes = mutableListOf<CustomSiteEdit>()
    private val writtenSites = mutableListOf<String>()
    private var running = 0
    private var mostAtOnce = 0
    private var gate: CompletableDeferred<Unit>? = null
    private var failNext = false

    private fun TestScope.edits(timers: CoroutineScope = backgroundScope) = SiteLiveEdits(timers, backgroundScope, sites::get) { sourceId, edit ->
        running++
        mostAtOnce = maxOf(mostAtOnce, running)
        gate?.await()
        writes += edit
        writtenSites += sourceId
        running--
        !failNext.also { failNext = false }
    }

    private fun named(label: String) = LiveFields(label, saved.includedInUnifiedAndForYou)

    @Test fun `a revert made while a write runs is written after it, one write at a time`() = runTest {
        val edits = edits()
        gate = CompletableDeferred()
        edits.schedule(saved.sourceId, LiveField.LABEL, named("Safer"), Duration.ZERO)
        runCurrent()
        edits.schedule(saved.sourceId, LiveField.LABEL, named(saved.label), Duration.ZERO)
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
        edits.schedule(saved.sourceId, LiveField.LABEL, named("Safer"), Duration.ZERO)
        runCurrent()
        edits.schedule(saved.sourceId, LiveField.LABEL, named("Safest"), TYPING_SAVE_DELAY)
        ownerScope.cancel()
        edits.flush()
        gate?.complete(Unit)
        runCurrent()
        assertEquals(listOf(CustomSiteEdit(label = "Safer"), CustomSiteEdit(label = "Safest")), writes)
    }

    @Test fun `a site's last change flushed during a write is not replaced by another site's change`() = runTest {
        val edits = edits()
        gate = CompletableDeferred()
        edits.schedule(saved.sourceId, LiveField.LABEL, named("Safer"), Duration.ZERO)
        runCurrent()
        edits.schedule(saved.sourceId, LiveField.LABEL, named("Safest"), TYPING_SAVE_DELAY)
        edits.flush()
        edits.schedule(other.sourceId, LiveField.LABEL, LiveFields("Gel", other.includedInUnifiedAndForYou), Duration.ZERO)
        runCurrent()
        gate?.complete(Unit)
        runCurrent()
        assertEquals(listOf(saved.sourceId, saved.sourceId, other.sourceId), writtenSites)
        assertEquals(listOf("Safer", "Safest", "Gel"), writes.map { it.label })
    }

    @Test fun `an agent saved elsewhere after a write is replaced when the user sets the first agent again`() = runTest {
        val edits = edits()
        edits.schedule(saved.sourceId, LiveField.USER_AGENT, LiveFields(saved.label, saved.includedInUnifiedAndForYou, userAgent = "U1"), Duration.ZERO)
        runCurrent()
        sites[saved.sourceId] = saved.copy(headers = SiteHeaders(userAgent = "U2"))
        edits.schedule(saved.sourceId, LiveField.USER_AGENT, LiveFields(saved.label, saved.includedInUnifiedAndForYou, userAgent = "U1"), Duration.ZERO)
        runCurrent()
        assertEquals(listOf(HeaderEdit.Replace("U1"), HeaderEdit.Replace("U1")), writes.map { it.userAgent })
    }

    @Test fun `a rename queued while a browser check saves a new agent keeps the new agent`() = runTest {
        val edits = edits()
        gate = CompletableDeferred()
        edits.schedule(saved.sourceId, LiveField.INCLUDE, LiveFields(saved.label, include = true), Duration.ZERO)
        runCurrent()
        edits.schedule(saved.sourceId, LiveField.LABEL, LiveFields("Safer", include = true), TYPING_SAVE_DELAY)
        edits.flush()
        sites[saved.sourceId] = saved.copy(headers = SiteHeaders(userAgent = "WebView/2"))
        gate?.complete(Unit)
        runCurrent()
        assertEquals(CustomSiteEdit(label = "Safer"), writes.last())
        assertEquals("WebView/2", writes.last().userAgent.applyTo(sites.getValue(saved.sourceId).headers.userAgent))
    }

    @Test fun `an agent queued beside an invalid referer is saved now, and the referer once corrected`() = runTest {
        val edits = edits()
        edits.schedule(saved.sourceId, LiveField.USER_AGENT, LiveFields(saved.label, false, userAgent = "U1"), TYPING_SAVE_DELAY)
        edits.schedule(saved.sourceId, LiveField.REFERER, LiveFields(saved.label, false, "U1", referer = "a\nb"), TYPING_SAVE_DELAY)
        advanceTimeBy(TYPING_SAVE_DELAY.inWholeMilliseconds + 1)
        edits.schedule(saved.sourceId, LiveField.REFERER, LiveFields(saved.label, false, "U1", "https://ref.example/"), Duration.ZERO)
        runCurrent()
        assertEquals(
            listOf(CustomSiteEdit(userAgent = HeaderEdit.Replace("U1")), CustomSiteEdit(referer = HeaderEdit.Replace("https://ref.example/"))),
            writes,
        )
    }

    @Test fun `an opt-in changed beside a blank name is saved, and the blank name never is`() = runTest {
        val edits = edits()
        edits.schedule(saved.sourceId, LiveField.LABEL, LiveFields(" ", include = false), TYPING_SAVE_DELAY)
        edits.schedule(saved.sourceId, LiveField.INCLUDE, LiveFields(" ", include = true), Duration.ZERO)
        runCurrent()
        edits.close()
        runCurrent()
        assertEquals(listOf(CustomSiteEdit(includeInUnifiedAndForYou = true)), writes)
    }

    @Test fun `a field whose write failed is written again with the site's next change`() = runTest {
        val edits = edits()
        failNext = true
        edits.schedule(saved.sourceId, LiveField.USER_AGENT, LiveFields(saved.label, false, userAgent = "U1"), Duration.ZERO)
        runCurrent()
        edits.schedule(saved.sourceId, LiveField.LABEL, LiveFields("Safer", false, userAgent = "U1"), Duration.ZERO)
        runCurrent()
        assertEquals(CustomSiteEdit(label = "Safer", userAgent = HeaderEdit.Replace("U1")), writes.last())
    }

    @Test fun `a change back to the saved value writes nothing`() = runTest {
        val edits = edits()
        edits.schedule(saved.sourceId, LiveField.LABEL, named("Safer"), TYPING_SAVE_DELAY)
        edits.schedule(saved.sourceId, LiveField.LABEL, named(saved.label), TYPING_SAVE_DELAY)
        edits.flush()
        runCurrent()
        assertEquals(emptyList<CustomSiteEdit>(), writes)
    }
}
