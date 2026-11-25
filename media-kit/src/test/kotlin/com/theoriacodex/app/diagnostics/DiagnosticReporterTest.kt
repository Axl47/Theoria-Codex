package com.theoriacodex.app.diagnostics

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DiagnosticReporterTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `the whole report passes through redaction, not only the stored log (ADR 0103)`() {
        val reporter = DiagnosticReporter(
            RuntimeEnvironment.getApplication(),
            DiagnosticLogStore(folder.root),
            appVersion = "1.0 api_key=SECRET123",
        )

        val report = runBlocking { reporter.build(setOf(DiagnosticSection.DEVICE)) }

        assertTrue(report, "App 1.0 api_key=" in report)
        assertFalse(report, "SECRET123" in report)
    }

    @Test
    fun `the system log section passes through redaction (ADR 0103)`() {
        val reporter = DiagnosticReporter(
            RuntimeEnvironment.getApplication(),
            DiagnosticLogStore(folder.root),
            appVersion = "1.0",
            readSystemLog = { "I Theoria: fetched https://cdn.example/private/path.jpg api_key=SECRET456\nI Theoria: player ready" },
        )

        val report = runBlocking { reporter.build(setOf(DiagnosticSection.SYSTEM_LOG)) }

        assertTrue(report, "I Theoria: player ready" in report)
        assertFalse(report, "SECRET456" in report)
        assertFalse(report, "private/path.jpg" in report)
    }
}
