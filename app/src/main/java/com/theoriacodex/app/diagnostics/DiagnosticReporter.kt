package com.theoriacodex.app.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.domain.diagnostics.DiagnosticRedaction
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The parts of a report a person can include or leave out before sharing it. */
enum class DiagnosticSection(val label: String, val summary: String) {
    DEVICE("Device and app", "App version, Android version and phone model"),
    CRASHES("Crashes", "Where the app crashed, from the last 5 crashes"),
    APP_EXITS("App exits", "Why Android recently closed the app, with freeze traces"),
    EVENTS("Failure log", "Failed requests, playback errors and other problems the app recorded"),
    SYSTEM_LOG("System log", "Recent Android messages from this app"),
}

private const val MAX_APP_EXITS = 8
private const val MAX_TRACE_CHARS = 64 * 1024
private const val MAX_SYSTEM_LOG_CHARS = 256 * 1024
private const val SYSTEM_LOG_TIMEOUT_SECONDS = 5L

/** Assembles the plain-text report meant to be read on a computer. Everything passes through redaction here. */
internal class DiagnosticReporter(private val context: Context, private val store: DiagnosticLogStore) {
    suspend fun build(sections: Set<DiagnosticSection>): String = withContext(Dispatchers.IO) {
        DiagnosticSection.entries.filter(sections::contains).joinToString(separator = "\n\n") { section ->
            "===== ${section.label} =====\n" + when (section) {
                DiagnosticSection.DEVICE -> device()
                DiagnosticSection.CRASHES -> store.readCrashes().joinToString("\n\n").ifBlank { "None recorded." }
                DiagnosticSection.APP_EXITS -> appExits()
                DiagnosticSection.EVENTS -> store.readEvents().ifBlank { "None recorded." }
                DiagnosticSection.SYSTEM_LOG -> systemLog()
            }
        }.let(DiagnosticRedaction::redact)
    }

    suspend fun clear() = withContext(Dispatchers.IO) { store.clear() }

    private fun device(): String {
        val memory = context.getSystemService(ActivityManager::class.java)
        return """
            Generated ${Instant.now()}
            App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}
            Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Device ${Build.MANUFACTURER} ${Build.MODEL} ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}
            Memory class ${memory.memoryClass} MB, large ${memory.largeMemoryClass} MB
        """.trimIndent()
    }

    private fun appExits(): String = context.getSystemService(ActivityManager::class.java)
        .getHistoricalProcessExitReasons(context.packageName, 0, MAX_APP_EXITS)
        .joinToString("\n\n") { exit ->
            val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching { exit.traceInputStream?.use { "\n" + it.readTail(MAX_TRACE_CHARS) } }.getOrNull().orEmpty()
            } else ""
            "${Instant.ofEpochMilli(exit.timestamp)} ${exitReasonName(exit.reason)} status=${exit.status} " +
                "importance=${exit.importance} rss=${exit.rss}KB ${exit.description.orEmpty()}$trace"
        }.ifBlank { "None recorded." }

    /** Apps can read only their own lines, so this needs no permission and cannot expose other apps. */
    private fun systemLog(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "2000").redirectErrorStream(true).start()
        try {
            val output = process.inputStream.readTail(MAX_SYSTEM_LOG_CHARS)
            process.waitFor(SYSTEM_LOG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            output
        } finally {
            process.destroy()
        }
    }.getOrElse { "Could not read the system log." }.ifBlank { "None recorded." }

    private fun InputStream.readTail(maxChars: Int): String = bufferedReader().use { it.readText() }.takeLast(maxChars)
}

private fun exitReasonName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_ANR -> "FROZEN (ANR)"
    ApplicationExitInfo.REASON_CRASH -> "CRASH"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USE"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
    ApplicationExitInfo.REASON_SIGNALED -> "KILLED_BY_SIGNAL"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
    ApplicationExitInfo.REASON_EXIT_SELF -> "EXITED"
    else -> "OTHER($reason)"
}
