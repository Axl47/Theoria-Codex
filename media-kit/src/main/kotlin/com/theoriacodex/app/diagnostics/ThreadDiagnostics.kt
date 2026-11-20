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

/** The checker [installOffMainChecker] installs; device tests read its counts. */
val mainThreadWorkChecker = MainThreadWorkChecker(
    isMainThread = { Looper.getMainLooper().isCurrentThread },
    report = ::reportMainThreadWork,
)

/**
 * Reports [OffMainWork] that runs on the main thread to logcat and the diagnostics log. Installed once, in the main
 * process only, next to the diagnostics log: no other process calls a source adapter.
 */
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

/**
 * StrictMode violations in debug builds, counted per class of the topmost app frame (its outer class, so lambdas
 * count with the code that wrote them) and reported once per distinct frame. Device tests read the counts.
 */
val strictModeViolations = ViolationLedger()

/**
 * Debug builds only: extends the calling thread's existing policy, so Android's network-on-main protection stays,
 * to report disk reads and writes. Call on the main thread. StrictMode sees disk access performed on the main thread,
 * not the main thread waiting for another thread's disk access.
 */
fun installDebugStrictMode() {
    if (!BuildConfig.DEBUG) return
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

/** Carries a call site's frames into logcat and the diagnostics log. Never thrown. */
private class CallSiteStack : Throwable("call site")
