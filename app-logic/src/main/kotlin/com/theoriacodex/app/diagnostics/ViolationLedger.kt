package com.theoriacodex.app.diagnostics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts violations per kind without limit and decides which ones to report: the first occurrence at each distinct
 * call site, for the first [siteLimit] sites in the process, then one notice that the limit was reached. Past the
 * limit a violation costs a counter increment, because [record] no longer asks for its site (no stack is captured).
 * One old offender therefore cannot hide new ones, and a repeating one stays cheap.
 */
class ViolationLedger(val siteLimit: Int = DEFAULT_SITE_LIMIT) {
    enum class Outcome { REPORT, LIMIT_REACHED, COUNT_ONLY }

    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val sites = HashSet<String>()

    @Volatile
    private var full = siteLimit <= 0
    private val limitNoticeSent = AtomicBoolean()

    /** How many times [kind] was recorded in this process, including those past the reporting limit. */
    fun count(kind: String): Long = counts[kind]?.get() ?: 0L

    /** Counts one violation of [kind]. [site] names its call site and is called only while sites are still reported. */
    fun record(kind: String, site: () -> String): Outcome {
        counts.computeIfAbsent(kind) { AtomicLong() }.incrementAndGet()
        if (full) return limitOutcome()
        val name = site()
        synchronized(sites) {
            if (full) return limitOutcome()
            if (!sites.add(name)) return Outcome.COUNT_ONLY
            if (sites.size >= siteLimit) full = true
        }
        return Outcome.REPORT
    }

    private fun limitOutcome() =
        if (limitNoticeSent.compareAndSet(false, true)) Outcome.LIMIT_REACHED else Outcome.COUNT_ONLY

    companion object {
        const val DEFAULT_SITE_LIMIT = 20
    }
}
