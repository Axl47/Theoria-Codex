package com.theoriacodex.app.di

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceReadinessTest {
    @Test fun `restored work waits until account availability is loaded and custom sites are built`() = runTest {
        val events = mutableListOf<String>()
        val customSites = CompletableDeferred<Unit>()
        val readiness = SourceReadiness(refreshAccounts = { events += "accounts" }, awaitCustomSites = { customSites.await() })
        val waiter = launch { readiness.await(); events += "ready" }
        runCurrent()
        assertTrue(events.isEmpty())

        readiness.open()
        runCurrent()
        assertEquals(listOf("accounts"), events)
        customSites.complete(Unit)
        waiter.join()
        assertEquals(listOf("accounts", "ready"), events)
    }
}
