package com.theoriacodex.app.journeys

import android.os.Looper
import com.theoriacodex.domain.diagnostics.OffMainChecker
import com.theoriacodex.domain.diagnostics.OffMainWork
import java.util.concurrent.atomic.AtomicInteger
import org.junit.rules.ExternalResource

/**
 * Counts source parses and those on the main thread for one test, forwarding every call to the app's checker.
 * Journey classes share a process, so the app's checker is restored after each test.
 */
class SourceParseRecorder : ExternalResource(), OffMainChecker {
    @Volatile private var appChecker: OffMainChecker? = null
    val parses = AtomicInteger()
    val mainThreadParses = AtomicInteger()

    override fun before() {
        appChecker = OffMainWork.install(this)
    }

    override fun after() {
        OffMainWork.install(appChecker)
    }

    /** Starts counting again from zero, so a journey can measure one step. */
    fun reset() {
        parses.set(0)
        mainThreadParses.set(0)
    }

    override fun check(label: String) {
        if (label == OffMainWork.SOURCE_PARSE) {
            parses.incrementAndGet()
            if (Looper.getMainLooper().isCurrentThread) mainThreadParses.incrementAndGet()
        }
        appChecker?.check(label)
    }
}
