package com.theoriacodex.app.diagnostics

import android.os.NetworkOnMainThreadException
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugStrictModeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun diskReadOnTheMainThreadIsCountedAtTheReadingCode() {
        val file = File(instrumentation.targetContext.cacheDir, "strictmode-probe").apply { writeText("probe") }
        val kind = DebugStrictModeDeviceTest::class.java.name
        val before = strictModeViolations.count(kind)

        instrumentation.runOnMainSync { file.readText() }

        val deadline = SystemClock.uptimeMillis() + LISTENER_TIMEOUT_MS
        while (strictModeViolations.count(kind) == before && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MS)
        }
        file.delete()
        assertTrue("No StrictMode violation counted for $kind", strictModeViolations.count(kind) > before)
    }

    @Test
    fun networkOnTheMainThreadStillThrows() {
        var error: Throwable? = null
        instrumentation.runOnMainSync {
            error = runCatching {
                val loopback = InetSocketAddress(InetAddress.getLoopbackAddress(), DISCARD_PORT)
                Socket().use { it.connect(loopback, CONNECT_TIMEOUT_MS) }
            }.exceptionOrNull()
        }
        assertTrue("Expected NetworkOnMainThreadException, got $error", error is NetworkOnMainThreadException)
    }
}

private const val LISTENER_TIMEOUT_MS = 5_000L
private const val POLL_MS = 50L
private const val DISCARD_PORT = 9
private const val CONNECT_TIMEOUT_MS = 1_000
