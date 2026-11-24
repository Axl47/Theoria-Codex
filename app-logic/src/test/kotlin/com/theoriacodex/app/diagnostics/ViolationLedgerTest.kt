package com.theoriacodex.app.diagnostics

import com.theoriacodex.app.diagnostics.ViolationLedger.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class ViolationLedgerTest {
    @Test
    fun `each site is reported once and every occurrence is counted (ADR 0073)`() {
        val ledger = ViolationLedger(siteLimit = 5)

        val outcomes = listOf("a", "b", "a", "a").map { site -> ledger.record("parse") { site } }

        assertEquals(listOf(Outcome.REPORT, Outcome.REPORT, Outcome.COUNT_ONLY, Outcome.COUNT_ONLY), outcomes)
        assertEquals(4L, ledger.count("parse"))
        assertEquals(0L, ledger.count("other"))
    }

    @Test
    fun `past the limit it notes the limit once, stops asking for sites and keeps counting (ADR 0073)`() {
        val ledger = ViolationLedger(siteLimit = 3)
        var sitesAsked = 0
        fun record(site: String) = ledger.record("parse") { sitesAsked++; site }

        val reported = listOf("a", "b", "c").map(::record)
        val afterLimit = listOf("d", "e", "a").map(::record)

        assertEquals(List(3) { Outcome.REPORT }, reported)
        assertEquals(listOf(Outcome.LIMIT_REACHED, Outcome.COUNT_ONLY, Outcome.COUNT_ONLY), afterLimit)
        assertEquals(3, sitesAsked)
        assertEquals(6L, ledger.count("parse"))
    }
}
