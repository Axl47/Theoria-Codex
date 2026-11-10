package com.theoriacodex.app.diagnostics

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store() = DiagnosticLogStore(folder.root.resolve("diagnostics"), now = { 1_000L })

    @Test
    fun `events reach disk redacted and survive a restart`() {
        val first = store()
        first.record("http", "failed", IOException("GET https://site.example/post?api_key=abc123"))
        first.readEvents()

        val events = store().readEvents()
        assertTrue(events, "[http] failed" in events)
        assertTrue(events, "site.example" in events)
        assertFalse(events, "abc123" in events)
    }

    @Test
    fun `identical failures collapse into a count`() {
        val store = store()
        repeat(4) { store.record("source", "down", null) }
        store.record("source", "other", null)

        val lines = store.readEvents().lines().filter(String::isNotBlank)
        assertEquals(3, lines.size)
        assertTrue(lines[1], "repeated 3 more times" in lines[1])
    }

    @Test
    fun `the log stays bounded`() {
        val store = store()
        repeat(4_000) { index ->
            store.record("area", "message $index " + "x y ".repeat(50), null)
            // Waiting keeps this below the flood limit, which would otherwise drop the newest lines.
            if (index % 100 == 0) store.readEvents()
        }

        val events = store.readEvents()
        assertTrue(events.length <= 2 * 256 * 1024 + 1_024)
        assertTrue(events, "message 3999" in events)
    }

    @Test
    fun `only the newest crashes are kept and clear removes everything`() {
        var time = 0L
        val store = DiagnosticLogStore(folder.root.resolve("diagnostics"), now = { ++time })
        repeat(7) { store.writeCrash(Thread.currentThread(), IllegalStateException("crash $it")) }

        val crashes = store.readCrashes()
        assertEquals(5, crashes.size)
        assertTrue(crashes.first(), "crash 6" in crashes.first())

        store.clear()
        assertTrue(store.readCrashes().isEmpty())
    }
}
