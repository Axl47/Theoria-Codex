package com.theoriacodex.extensionhost

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.diagnostics.Diagnostics
import eu.kanade.tachiyomi.network.HttpException
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostUnavailableException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionCallsTest {
    private val calls = ExtensionCalls("Ext") {}

    @After
    fun stopListeningToDiagnostics() {
        Diagnostics.install { _, _, _ -> }
    }

    @Test
    fun `an extension call runs off the caller's thread (ADR 0060)`() = runBlocking {
        val caller = Thread.currentThread()

        assertNotSame(caller, calls.calling { Thread.currentThread() })
    }

    @Test
    fun `a failure keeps the extension's own message, never its class name, and an adapter's exception passes unchanged (ADR 0060)`() = runBlocking {
        listOf(IllegalStateException("bad html"), IOException("offline"), NoSuchMethodError("missing"), IllegalStateException()).forEach { thrown ->
            assertEquals(thrown.message, assertFailsWith<SourceAdapterException> { calls.calling { throw thrown } }.message)
        }
        val adapter = SourceAdapterException(SourceFailureReason.NETWORK, "kept")

        assertSame(adapter, assertFailsWith<SourceAdapterException> { calls.calling { throw adapter } })
    }

    @Test
    fun `only a virtual machine error escapes an extension, and every other throwable is its failure (ADR 0060)`() = runBlocking {
        listOf(OutOfMemoryError("extension"), InternalError("extension"), StackOverflowError("extension")).forEach { thrown ->
            assertEquals(thrown.javaClass, assertFailsWith<VirtualMachineError> { calls.calling { throw thrown } }.javaClass)
            assertEquals(thrown.javaClass, assertFailsWith<VirtualMachineError> { failingAsIo { throw thrown } }.javaClass)
        }
        listOf(AssertionError("extension"), NotImplementedError("extension"), NoClassDefFoundError("extension"), Throwable("extension")).forEach { thrown ->
            assertEquals(thrown.javaClass, assertFailsWith<SourceAdapterException> { calls.calling { throw thrown } }.cause?.javaClass)
            assertEquals(thrown.javaClass, assertFailsWith<IOException> { failingAsIo { throw thrown } }.cause?.javaClass)
        }
    }

    @Test
    fun `a failure says whether the site was unreachable, slow, refusing or limiting (ADR 0220)`() {
        val reasons = listOf(
            HttpException(429) to SourceFailureReason.RATE_LIMITED,
            HttpException(403) to SourceFailureReason.BLOCKED,
            HostUnavailableException("site.test", HostOutageKind.CHALLENGE) to SourceFailureReason.BLOCKED,
            HttpException(500) to SourceFailureReason.NETWORK,
            SocketTimeoutException("slow") to SourceFailureReason.TIMED_OUT,
            InterruptedIOException("timeout") to SourceFailureReason.TIMED_OUT,
            IOException("offline") to SourceFailureReason.NETWORK,
            IllegalStateException("bad html") to SourceFailureReason.PARSE,
        )

        assertEquals(reasons.map { it.second }, reasons.map { calls.failure(it.first).reason })
    }

    @Test
    fun `only errors reach the diagnostics log, and a missing host class says so (ADR 0060)`() {
        val logged = mutableListOf<String>()
        Diagnostics.install { _, message, _ -> logged += message }

        listOf(IllegalStateException("bad html"), IOException("offline"), HttpException(500), NoSuchMethodError("missing"), AssertionError("broken")).forEach(calls::failure)

        assertEquals(listOf("Ext needs host API this module lacks", "Ext failed"), logged)
    }

    @Test
    fun `time-limited extension work gets 32 threads of its own beside the IO pool (ADR 0060)`() = runBlocking {
        val release = CountDownLatch(1)
        val detachedStarted = AtomicInteger()
        val ioStarted = CountDownLatch(IO_THREADS)
        try {
            repeat(DETACHED_THREADS + 1) { HostServices(ExtensionImages()).detached.launch { detachedStarted.incrementAndGet(); release.await() } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (detachedStarted.get() < DETACHED_THREADS && System.nanoTime() < deadline) Thread.sleep(10)
            repeat(IO_THREADS) { launch(Dispatchers.IO) { ioStarted.countDown(); release.await() } }

            assertTrue(ioStarted.await(5, TimeUnit.SECONDS))
            assertEquals(DETACHED_THREADS, detachedStarted.get())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `the listed-object map keeps the latest 2048 addresses and forgets the oldest (ADR 0064)`() {
        val listed = recentlyListed<Int>()
        repeat(2_048) { listed["/$it"] = it }
        assertEquals(2_048, listed.size)

        listed["/2048"] = 2_048

        assertEquals(2_048, listed.size)
        assertFalse("/0" in listed)
        assertTrue("/1" in listed)
    }

    private companion object {
        const val DETACHED_THREADS = 32
        const val IO_THREADS = 64
    }
}
