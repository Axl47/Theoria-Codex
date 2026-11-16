package com.theoriacodex.app.diagnostics

import android.os.Looper
import android.util.Log
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.diagnostics.OffMainWork

private const val LOG_TAG = "TheoriaThreads"
private const val MAIN_THREAD_AREA = "main-thread"

/** The checker [installOffMainChecker] installs; device tests read its counts. */
internal val mainThreadWorkChecker = MainThreadWorkChecker(
    isMainThread = { Looper.getMainLooper().isCurrentThread },
    report = ::reportMainThreadWork,
)

/**
 * Reports [OffMainWork] that runs on the main thread to logcat and the diagnostics log. Installed once, in the main
 * process only, next to the diagnostics log: no other process calls a source adapter.
 */
internal fun installOffMainChecker() {
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

private fun reportLimit(area: String, siteLimit: Int) {
    val message = "Reported $siteLimit call sites; later ones are counted, not reported"
    Log.w(LOG_TAG, "[$area] $message")
    Diagnostics.report(area, message)
}

/** Carries a call site's frames into logcat and the diagnostics log. Never thrown. */
private class CallSiteStack : Throwable("call site")
