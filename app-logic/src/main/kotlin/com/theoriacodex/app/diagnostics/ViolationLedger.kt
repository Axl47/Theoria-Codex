package com.theoriacodex.app.diagnostics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ViolationLedger(val siteLimit: Int = DEFAULT_SITE_LIMIT) {
    enum class Outcome { REPORT, LIMIT_REACHED, COUNT_ONLY }

    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val sites = HashSet<String>()

    @Volatile
    private var full = siteLimit <= 0
    private val limitNoticeSent = AtomicBoolean()

    fun count(kind: String): Long = counts[kind]?.get() ?: 0L

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
