package com.theoriacodex.app.diagnostics

import android.os.Looper
import android.os.StrictMode
import android.os.strictmode.Violation
import android.util.Log
import com.theoriacodex.mediakit.BuildConfig
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.diagnostics.OffMainWork
import java.util.concurrent.Executors

private const val LOG_TAG = "TheoriaThreads"
private const val MAIN_THREAD_AREA = "main-thread"
private const val STRICT_MODE_AREA = "strictmode"
private const val APP_PACKAGE_PREFIX = "com.theoriacodex."

val mainThreadWorkChecker = MainThreadWorkChecker(
    isMainThread = { Looper.getMainLooper().isCurrentThread },
    report = ::reportMainThreadWork,
)

fun installOffMainChecker() {
    OffMainWork.install(mainThreadWorkChecker)
}

private fun reportMainThreadWork(report: MainThreadWorkReport) = when (report) {
    is MainThreadWorkReport.CallSite -> {
        val callSite = CallSiteStack().apply { stackTrace = report.frames.toTypedArray() }
        val message = "${report.label} ran on the main thread"
        Log.w(LOG_TAG, message, callSite)
        Diagnostics.report(MAIN_THREAD_AREA, message, callSite)
    }
    is MainThreadWorkReport.LimitReached -> reportLimit(MAIN_THREAD_AREA, report.siteLimit)
}

val debugDiagnosticsEnabled: Boolean = BuildConfig.DEBUG

val strictModeViolations = ViolationLedger()

fun installDebugStrictMode() {
    if (!debugDiagnosticsEnabled) return
    val listenerThread = Executors.newSingleThreadExecutor { task ->
        Thread(task, "theoria-strictmode").apply { isDaemon = true }
    }
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy())
            .detectDiskReads()
            .detectDiskWrites()
            .penaltyListener(listenerThread, ::reportStrictModeViolation)
            .build(),
    )
}

private fun reportStrictModeViolation(violation: Violation) {
    val appFrame = violation.stackTrace.firstOrNull { it.className.startsWith(APP_PACKAGE_PREFIX) }
        ?: violation.stackTrace.firstOrNull()
    val kind = appFrame?.className?.substringBefore('$') ?: "unknown"
    when (strictModeViolations.record(kind) { appFrame.toString() }) {
        ViolationLedger.Outcome.REPORT -> {
            val message = "${violation.javaClass.simpleName} at $appFrame"
            Log.w(LOG_TAG, "[$STRICT_MODE_AREA] $message", violation)
            Diagnostics.report(STRICT_MODE_AREA, message, violation)
        }
        ViolationLedger.Outcome.LIMIT_REACHED -> reportLimit(STRICT_MODE_AREA, strictModeViolations.siteLimit)
        ViolationLedger.Outcome.COUNT_ONLY -> Unit
    }
}

private fun reportLimit(area: String, siteLimit: Int) {
    val message = "Reported $siteLimit call sites; later ones are counted, not reported"
    Log.w(LOG_TAG, "[$area] $message")
    Diagnostics.report(area, message)
}

private class CallSiteStack : Throwable("call site")
