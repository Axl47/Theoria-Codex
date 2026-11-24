package com.theoriacodex.data.storage

import com.theoriacodex.data.repository.DataStoreSettingsRepository
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreScopesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `a store reopens on the same file while the last scope's write was in flight (ADR 0032)`() = runBlocking {
        val directory = temporary.newFolder()
        repeat(REOPENS) { round ->
            withDataStoreScope { scope ->
                val settings = DataStoreSettingsRepository(directory, scope)
                settings.awaitReady()
                val writing = CompletableDeferred<Unit>()
                scope.launch {
                    settings.updateSettings { current ->
                        writing.complete(Unit)
                        current.copy(oldSearchBar = round % 2 == 0)
                    }
                }
                withTimeout(DATASTORE_CLOSE_TIMEOUT) { writing.await() }
            }
        }
    }

    @Test fun `closing a scope whose work never ends fails instead of hanging (ADR 0032)`() = runBlocking {
        val scope = newDataStoreScope()
        val stuck = scope.stuckChild()
        try {
            assertTrue(runCatching { scope.closeDataStores(100.milliseconds) }.exceptionOrNull() is IllegalStateException)
        } finally {
            stuck.complete(Unit)
        }
    }

    @Test fun `a block that fails keeps its own failure when its scope also fails to close (ADR 0032)`() = runBlocking {
        val stuck = CompletableDeferred<Unit>()
        val failure = try {
            runCatching {
                withDataStoreScope { scope ->
                    scope.stuckChild(release = stuck)
                    throw IllegalArgumentException("the block's own failure")
                }
            }.exceptionOrNull()
        } finally {
            stuck.complete(Unit)
        }

        assertEquals("the block's own failure", failure?.message)
    }

    private suspend fun CoroutineScope.stuckChild(release: CompletableDeferred<Unit> = CompletableDeferred()): CompletableDeferred<Unit> {
        val started = CompletableDeferred<Unit>()
        launch { withContext(NonCancellable) { started.complete(Unit); release.await() } }
        started.await()
        return release
    }

    private companion object {
        const val REOPENS = 300
    }
}
