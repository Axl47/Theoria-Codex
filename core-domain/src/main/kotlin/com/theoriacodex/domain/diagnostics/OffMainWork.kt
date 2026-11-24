package com.theoriacodex.domain.diagnostics

import java.util.concurrent.atomic.AtomicReference

fun interface OffMainChecker {
    fun check(label: String)
}

object OffMainWork {
    const val SOURCE_PARSE = "source-parse"

    const val SELF_TEST = "self-test"

    private val checker = AtomicReference<OffMainChecker?>()

    fun install(checker: OffMainChecker?): OffMainChecker? = this.checker.getAndSet(checker)

    fun note(label: String) {
        checker.get()?.check(label)
    }
}
