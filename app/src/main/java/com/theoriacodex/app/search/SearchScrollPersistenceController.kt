package com.theoriacodex.app.search

import com.theoriacodex.data.repository.SearchScrollState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SearchScrollPersistenceTarget(
    val queryHash: String,
    val state: SearchScrollState,
)

/**
 * Navigation-owner closeable for debounced scroll writes. Its scheduler is independent from
 * viewModelScope so ViewModel teardown can cancel route work first and still synchronously drain
 * the final pending position through the durable repository.
 */
internal class SearchScrollPersistenceController(
    dispatcher: CoroutineDispatcher,
    private val debounceMillis: Long,
    private val persist: suspend (SearchScrollPersistenceTarget) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val commitMutex = Mutex()
    private var activeJob: Job? = null
    private var latest: SearchScrollPersistenceTarget? = null
    private var committed: SearchScrollPersistenceTarget? = null
    private var closed = false

    fun submit(target: SearchScrollPersistenceTarget) {
        synchronized(lock) {
            if (closed || target == latest) return
            latest = target
            activeJob?.cancel(CancellationException("Search scroll position superseded"))
            activeJob = scope.launch {
                if (debounceMillis > 0) delay(debounceMillis)
                commit(target)
            }
        }
    }

    /**
     * ViewModel.clear() has no suspending completion hook. The caller therefore waits for the one
     * final DataStore acknowledgement on Dispatchers.IO before the owned scheduler is cancelled.
     * This can delay teardown by the duration of that storage operation; imposing a timeout would
     * make the final position lossy again, so storage failure is surfaced instead of abandoned.
     */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            activeJob?.cancel(CancellationException("Search owner closed"))
        }
        try {
            runBlocking(Dispatchers.IO) {
                val pending = synchronized(lock) { latest?.takeIf { target -> target != committed } }
                if (pending != null) commit(pending)
            }
        } finally {
            scope.cancel(CancellationException("Search scroll scheduler closed"))
        }
    }

    private suspend fun commit(target: SearchScrollPersistenceTarget) {
        withContext(NonCancellable) {
            commitMutex.withLock {
                if (synchronized(lock) { committed == target }) return@withLock
                persist(target)
                synchronized(lock) { committed = target }
            }
        }
    }
}
