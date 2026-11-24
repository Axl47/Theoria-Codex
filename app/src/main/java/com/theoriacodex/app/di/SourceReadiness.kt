package com.theoriacodex.app.di

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.CompletableDeferred

internal class SourceReadiness(
    private val refreshAccounts: suspend () -> Unit,
    private val awaitCustomSites: suspend () -> Unit,
) {
    private val opened = CompletableDeferred<Unit>()

    suspend fun open() {
        runCatchingPreservingCancellation { refreshAccounts() }
        opened.complete(Unit)
    }

    suspend fun await() {
        opened.await()
        awaitCustomSites()
    }
}
