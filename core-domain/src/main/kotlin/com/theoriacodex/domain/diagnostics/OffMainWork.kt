package com.theoriacodex.domain.diagnostics

import java.util.concurrent.atomic.AtomicReference

/** Decides what happens when work marked with [OffMainWork.note] runs. Must never throw. */
fun interface OffMainChecker {
    fun check(label: String)
}

/**
 * Marks work that must never run on the main thread, such as parsing a provider response. Call [note] where that
 * work executes (the first line of a parse helper), never at the start of a suspend function that callers may
 * correctly invoke on the main thread before it switches. The app installs a checker that reports main-thread runs;
 * without one, a note costs one volatile read.
 */
object OffMainWork {
    /** Parsing a provider response: JSON, HTML or XML. */
    const val SOURCE_PARSE = "source-parse"

    /** The permanent control: a device test notes it on the main thread to prove the app's checker reports. */
    const val SELF_TEST = "self-test"

    private val checker = AtomicReference<OffMainChecker?>()

    /** Installs [checker] and returns the one it replaced, so a test can wrap the app's checker and put it back. */
    fun install(checker: OffMainChecker?): OffMainChecker? = this.checker.getAndSet(checker)

    /** [label] is one of the constants above, never data. */
    fun note(label: String) {
        checker.get()?.check(label)
    }
}
