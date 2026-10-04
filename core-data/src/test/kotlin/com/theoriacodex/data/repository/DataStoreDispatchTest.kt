package com.theoriacodex.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreDispatchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `storage transaction and shutdown do not need the caller dispatcher to keep running`() = runBlocking {
        val storageDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val storageJob = SupervisorJob()
        val storageScope = CoroutineScope(storageJob + storageDispatcher)
        val repository = DataStoreSettingsRepository(temporary.newFolder(), storageScope)
        val callerJob = SupervisorJob()
        val callerScheduler = TestCoroutineScheduler()
        val caller = CoroutineScope(callerJob + StandardTestDispatcher(callerScheduler))
        val transformed = CompletableDeferred<Unit>()
        val releaseStorage = CountDownLatch(1)
        try {
            repository.awaitReady()
            val storageParked = CompletableDeferred<Unit>()
            storageScope.launch { storageParked.complete(Unit); releaseStorage.await() }
            withTimeout(5_000) { storageParked.await() }
            caller.launch {
                repository.updateSettings { current ->
                    transformed.complete(Unit)
                    current.copy(cache = current.cache.copy(downloadsOverMetered = !current.cache.downloadsOverMetered))
                }
            }
            callerScheduler.runCurrent()
            // Freeze the UI after submission. The actor must execute its transform and close independently.
            releaseStorage.countDown()
            withTimeout(5_000) { transformed.await() }
            withTimeout(5_000) { storageJob.cancelAndJoin() }
        } finally {
            releaseStorage.countDown()
            callerJob.cancel()
            callerScheduler.advanceUntilIdle()
            try {
                withTimeout(5_000) { storageJob.cancelAndJoin() }
            } finally {
                storageDispatcher.close()
            }
        }
    }
}
