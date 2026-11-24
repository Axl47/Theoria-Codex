package com.theoriacodex.data.storage

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

val DATASTORE_CLOSE_TIMEOUT: Duration = 5.seconds

fun newDataStoreScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

suspend fun CoroutineScope.closeDataStores(timeout: Duration = DATASTORE_CLOSE_TIMEOUT) {
    check(tryCloseDataStores(timeout)) { "DataStore scope did not close within $timeout" }
}

suspend fun <T> withDataStoreScope(block: suspend (CoroutineScope) -> T): T {
    val scope = newDataStoreScope()
    val outcome = try {
        block(scope)
    } catch (failure: Throwable) {
        if (!scope.tryCloseDataStores(DATASTORE_CLOSE_TIMEOUT)) {
            failure.addSuppressed(IllegalStateException("DataStore scope did not close within $DATASTORE_CLOSE_TIMEOUT"))
        }
        throw failure
    }
    scope.closeDataStores()
    return outcome
}

private suspend fun CoroutineScope.tryCloseDataStores(timeout: Duration): Boolean {
    val owner = coroutineContext.job
    val released = CompletableDeferred<Unit>()
    owner.invokeOnCompletion { released.complete(Unit) }
    owner.cancel()
    return withContext(NonCancellable + Dispatchers.IO) { withTimeoutOrNull(timeout) { released.await() } != null }
}
