package com.theoriacodex.app.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.app.TheoriaApplication
import com.theoriacodex.domain.diagnostics.OffMainWork
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainThreadWorkCheckerDeviceTest {
    @Test
    fun startupInstallsTheCheckerAndItReportsMainThreadWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as TheoriaApplication
        val startedAtMs = System.currentTimeMillis()
        val runsBefore = mainThreadWorkChecker.mainThreadRuns(OffMainWork.SELF_TEST)

        instrumentation.runOnMainSync { OffMainWork.note(OffMainWork.SELF_TEST) }

        assertEquals(runsBefore + 1, mainThreadWorkChecker.mainThreadRuns(OffMainWork.SELF_TEST))
        val reporter = checkNotNull(application.diagnosticReporter) { "Diagnostics did not start in the main process" }
        val events = runBlocking { reporter.build(setOf(DiagnosticSection.EVENTS)) }
        val reported = events.lines().any { line ->
            "[main-thread] ${OffMainWork.SELF_TEST} ran on the main thread" in line &&
                Instant.parse(line.substringBefore(' ')).toEpochMilli() >= startedAtMs
        }
        assertTrue("No main-thread report since the test started:\n$events", reported)
    }
}
