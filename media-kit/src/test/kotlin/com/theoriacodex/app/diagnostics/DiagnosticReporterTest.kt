package com.theoriacodex.app.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DiagnosticReporterTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `every section of the report passes through redaction (ADR 0103)`() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setReason(ApplicationExitInfo.REASON_CRASH)
                .setTimestamp(1_000L)
                .setDescription("lost https://cdn.example/private/exit.jpg api_key=EXITSECRET")
                .build(),
        )
        folder.root.resolve("crash-1.txt").writeText("crashed at https://cdn.example/private/crash.jpg api_key=CRASHSECRET")
        folder.root.resolve("events.log").writeText("failed https://cdn.example/private/event.jpg api_key=EVENTSECRET\n")
        val reporter = DiagnosticReporter(
            context,
            DiagnosticLogStore(folder.root),
            appVersion = "1.0 api_key=SECRET123",
            readSystemLog = { "I Theoria: fetched https://cdn.example/private/log.jpg api_key=LOGSECRET\nI Theoria: player ready" },
        )

        val report = runBlocking { reporter.build(DiagnosticSection.entries.toSet()) }

        assertTrue(report, "CRASH" in report)
        assertTrue(report, "App 1.0 api_key=" in report)
        assertTrue(report, "I Theoria: player ready" in report)
        assertTrue(report, "crashed at" in report)
        assertTrue(report, "failed " in report)
        listOf(
            "SECRET123", "EXITSECRET", "private/exit.jpg", "LOGSECRET", "private/log.jpg",
            "CRASHSECRET", "private/crash.jpg", "EVENTSECRET", "private/event.jpg",
        ).forEach { secret ->
            assertFalse(report, secret in report)
        }
    }
}
