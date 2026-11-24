package com.theoriacodex.app.diagnostics

import android.app.Application
import android.os.StrictMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ThreadDiagnosticsTest {
    @Test
    fun `debug StrictMode adds disk checks and keeps the thread's existing protections (ADR 0103)`() {
        val original = StrictMode.getThreadPolicy()
        try {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeathOnNetwork().build())
            val existing = mask(StrictMode.getThreadPolicy())
            val withDisk = mask(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().build())

            installDebugStrictMode()

            assertEquals(existing or withDisk, mask(StrictMode.getThreadPolicy()) and (existing or withDisk))
        } finally {
            StrictMode.setThreadPolicy(original)
        }
    }

    private fun mask(policy: StrictMode.ThreadPolicy) = Regex("""mask=(-?\d+)""").find(policy.toString())!!.groupValues[1].toInt()
}
