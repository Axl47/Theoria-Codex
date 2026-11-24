package com.theoriacodex.data.storage

import com.theoriacodex.data.repository.DataStoreSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreScopesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `a store reopens on the same file while the last scope's write was in flight`() = runBlocking {
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

    private companion object {
        const val REOPENS = 300
    }
}
