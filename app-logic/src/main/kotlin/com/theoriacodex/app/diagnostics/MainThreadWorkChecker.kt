package com.theoriacodex.app.diagnostics

import com.theoriacodex.domain.diagnostics.OffMainChecker
import com.theoriacodex.domain.diagnostics.OffMainWork

/**
 * The app's [OffMainChecker]. Off the main thread it does nothing. On the main thread it counts the run under its
 * label and, through [ledger], reports the first run from each distinct call site: the label plus the top frames
 * below [OffMainWork.note]. Never throws.
 *
 * In minified builds frames are renamed and note may be inlined away, so distinct callers can merge into one site;
 * there a report means "a violation exists under this label", to be reproduced in a debug build.
 */
class MainThreadWorkChecker(
    private val isMainThread: () -> Boolean,
    private val report: (MainThreadWorkReport) -> Unit,
    private val ledger: ViolationLedger = ViolationLedger(),
) : OffMainChecker {
    /** Main-thread runs of [label] in this process, never capped. */
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
    /** The first main-thread run of [label] from this call site; [frames] start at the code that called note. */
    data class CallSite(val label: String, val frames: List<StackTraceElement>) : MainThreadWorkReport

    /** [siteLimit] sites were reported; later runs are only counted. */
    data class LimitReached(val siteLimit: Int) : MainThreadWorkReport
}

/** The frames below [OffMainWork.note], or the top of the whole stack when note was inlined away. */
internal fun callSiteFrames(stack: Array<StackTraceElement>): List<StackTraceElement> {
    val marker = stack.indexOfLast { it.className == OffMainWork::class.java.name }
    return stack.drop(marker + 1).take(REPORTED_FRAMES)
}

/** Frames that tell call sites apart: the marked helper and the callers above it. */
private const val CALL_SITE_DEPTH = 4
private const val REPORTED_FRAMES = 16
