package com.theoriacodex.app.diagnostics

import com.theoriacodex.domain.diagnostics.OffMainChecker
import com.theoriacodex.domain.diagnostics.OffMainWork
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MainThreadWorkCheckerTest {
    private var onMain = true
    private val reports = mutableListOf<MainThreadWorkReport>()
    private val checker = MainThreadWorkChecker(isMainThread = { onMain }, report = reports::add)
    private var previous: OffMainChecker? = null

    @Before
    fun installChecker() {
        previous = OffMainWork.install(checker)
    }

    @After
    fun restoreChecker() {
        OffMainWork.install(previous)
    }

    @Test
    fun `off the main thread nothing is counted or reported`() {
        onMain = false
        firstSite()

        assertEquals(0L, checker.mainThreadRuns(OffMainWork.SOURCE_PARSE))
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `each call site is reported once with frames starting at its caller`() {
        repeat(3) { firstSite() }
        secondSite()

        assertEquals(4L, checker.mainThreadRuns(OffMainWork.SOURCE_PARSE))
        val sites = reports.map { (it as MainThreadWorkReport.CallSite).frames.first().methodName }
        assertEquals(listOf("firstSite", "secondSite"), sites)
    }

    private fun firstSite() = OffMainWork.note(OffMainWork.SOURCE_PARSE)

    private fun secondSite() = OffMainWork.note(OffMainWork.SOURCE_PARSE)
}
