package com.theoriacodex.app.diagnostics

import com.theoriacodex.domain.diagnostics.OffMainChecker
import com.theoriacodex.domain.diagnostics.OffMainWork

class MainThreadWorkChecker(
    private val isMainThread: () -> Boolean,
    private val report: (MainThreadWorkReport) -> Unit,
    private val ledger: ViolationLedger = ViolationLedger(),
) : OffMainChecker {
    fun mainThreadRuns(label: String): Long = ledger.count(label)

    override fun check(label: String) {
        if (!isMainThread()) return
        runCatching {
            var frames = emptyList<StackTraceElement>()
            val outcome = ledger.record(label) {
                frames = callSiteFrames(Thread.currentThread().stackTrace)
                frames.take(CALL_SITE_DEPTH).joinToString(prefix = "$label@")
            }
            when (outcome) {
                ViolationLedger.Outcome.REPORT -> report(MainThreadWorkReport.CallSite(label, frames))
                ViolationLedger.Outcome.LIMIT_REACHED -> report(MainThreadWorkReport.LimitReached(ledger.siteLimit))
                ViolationLedger.Outcome.COUNT_ONLY -> Unit
            }
        }
    }
}

sealed interface MainThreadWorkReport {
    data class CallSite(val label: String, val frames: List<StackTraceElement>) : MainThreadWorkReport

    data class LimitReached(val siteLimit: Int) : MainThreadWorkReport
}

internal fun callSiteFrames(stack: Array<StackTraceElement>): List<StackTraceElement> {
    val marker = stack.indexOfLast { it.className == OffMainWork::class.java.name }
    return stack.drop(marker + 1).take(REPORTED_FRAMES)
}

private const val CALL_SITE_DEPTH = 4
private const val REPORTED_FRAMES = 16
