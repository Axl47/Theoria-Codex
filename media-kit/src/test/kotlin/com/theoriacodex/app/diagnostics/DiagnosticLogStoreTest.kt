package com.theoriacodex.app.diagnostics

import java.io.IOException
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun `events reach disk redacted and survive a restart (ADR 0002, 0103)`() {
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
    fun `the log stays bounded (ADR 0103)`() {
        val store = store()
        repeat(4_000) { index ->
            store.record("area", "message $index " + "x y ".repeat(50), null)
            if (index % 100 == 0) store.readEvents()
        }

        val events = store.readEvents()
        assertTrue(events.length <= 2 * 256 * 1024 + 1_024)
        assertTrue(events, "message 3999" in events)
    }

    @Test
    fun `only the newest crashes are kept and clear removes everything (ADR 0103)`() {
        var time = 0L
        val store = DiagnosticLogStore(folder.root.resolve("diagnostics"), now = { ++time })
        repeat(7) { store.writeCrash(Thread.currentThread(), IllegalStateException("crash $it")) }
        store.record("area", "an event", null)

        val crashes = store.readCrashes()
        assertEquals(5, crashes.size)
        assertTrue(crashes.first(), "crash 6" in crashes.first())

        store.clear()
        assertTrue(store.readCrashes().isEmpty())
        assertEquals("", store.readEvents())
        assertTrue(folder.root.resolve("diagnostics").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a crash reaches disk redacted (ADR 0103)`() {
        val store = store()
        store.writeCrash(Thread.currentThread(), IllegalStateException("GET https://site.example/post?api_key=abc123"))

        val crash = store.readCrashes().single()
        assertTrue(crash, "site.example" in crash)
        assertFalse(crash, "abc123" in crash)
    }

    @Test
    fun `a flood past the pending cap keeps the earliest lines and drops the newest, off the caller's thread (ADR 0103)`() {
        val store = store()
        val release = CountDownLatch(1)
        var writerThread: Thread? = null
        store.record("area", "message 0", heldOnTheWriter(release) { writerThread = Thread.currentThread() })
        try {
            (1..600).forEach { store.record("area", "message $it", null) }
        } finally {
            release.countDown()
        }

        val kept = Regex("""\[area] message (\d+)""").findAll(store.readEvents()).map { it.groupValues[1].toInt() }.toList()
        assertEquals((0..511).toList(), kept)
        assertTrue(writerThread != null && writerThread != Thread.currentThread())
    }

    @Test
    fun `a read waits for every event recorded before it (ADR 0103)`() {
        val store = store()
        val release = CountDownLatch(1)
        store.record("area", "held", heldOnTheWriter(release) {})
        store.record("area", "after", null)
        val reader = Thread.currentThread()
        Thread {
            while (reader.state != Thread.State.WAITING && reader.state != Thread.State.TIMED_WAITING) Thread.onSpinWait()
            release.countDown()
        }.start()

        val events = store.readEvents()

        assertTrue(events, "[area] held" in events)
        assertTrue(events, "[area] after" in events)
    }

    @Test
    fun `a crash is on disk before the previous handler ends the process (ADR 0103)`() {
        val directory = folder.root.resolve("diagnostics")
        val store = DiagnosticLogStore(directory, now = { 1_000L })
        val release = CountDownLatch(1)
        store.record("area", "held", heldOnTheWriter(release) {})
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val handedOver = mutableListOf<Pair<Throwable, Boolean>>()
        val crash = IllegalStateException("crash on the main thread")
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, error ->
                handedOver += error to (directory.listFiles().orEmpty().any { it.name.startsWith("crash-") })
            }
            installCrashCapture(store)

            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
            release.countDown()
        }

        assertEquals(listOf(crash to true), handedOver)
        assertTrue("crash on the main thread" in store.readCrashes().single())
    }

    @Test
    fun `a crash that cannot be written still reaches the previous handler (ADR 0103)`() {
        val directory = folder.root.resolve("diagnostics").apply { mkdirs() }
        directory.resolve("crash-1000.txt").mkdirs()
        val store = DiagnosticLogStore(directory, now = { 1_000L })
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val handedOver = mutableListOf<Throwable>()
        val crash = IllegalStateException("crash on the main thread")
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, error -> handedOver += error }
            installCrashCapture(store)

            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }

        assertEquals(listOf<Throwable>(crash), handedOver)
    }

    @Test
    fun `clear waits for events recorded before it and removes them (ADR 0103)`() {
        val store = store()
        val release = CountDownLatch(1)
        store.record("area", "held", heldOnTheWriter(release) {})
        store.record("area", "before clear", null)
        val clearing = Thread.currentThread()
        val releaser = Thread {
            while (clearing.state != Thread.State.WAITING && clearing.state != Thread.State.TIMED_WAITING) Thread.onSpinWait()
            release.countDown()
        }.apply { start() }

        store.clear()
        releaser.join(10_000)

        assertEquals("", store.readEvents())
    }

    private fun heldOnTheWriter(release: CountDownLatch, onWriter: () -> Unit) = object : IllegalStateException("held") {
        override fun printStackTrace(s: PrintWriter) {
            onWriter()
            release.await(10, TimeUnit.SECONDS)
        }
    }
}
