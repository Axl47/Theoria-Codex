package com.theoriacodex.app.di

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.CompletableDeferred

/**
 * When restored work (queued downloads, offline jobs) may resolve posts through the source registry. The container
 * calls [open] once at the end of startup: it loads account availability, so sources that need a saved key (such as
 * Rule34.xxx) are in the registry, the same refresh the shell runs on startup. [await] then also waits until custom
 * sites are built into adapters.
 */
internal class SourceReadiness(
    private val refreshAccounts: suspend () -> Unit,
    private val awaitCustomSites: suspend () -> Unit,
) {
    private val opened = CompletableDeferred<Unit>()

    /** Loads account availability, then lets [await] return. A failed read still opens: the shell retries it. */
    suspend fun open() {
        runCatchingPreservingCancellation { refreshAccounts() }
        opened.complete(Unit)
    }

    /** Returns once [open] has run and custom sites are built. */
    suspend fun await() {
        opened.await()
        awaitCustomSites()
    }
}
