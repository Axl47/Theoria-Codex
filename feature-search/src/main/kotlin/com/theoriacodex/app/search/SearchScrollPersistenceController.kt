package com.theoriacodex.app.search

import com.theoriacodex.data.repository.SearchScrollState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SearchScrollPersistenceTarget(
    val queryHash: String,
    val state: SearchScrollState,
)

internal fun SearchCoordinator.scrollPersistence(
    dispatcher: CoroutineDispatcher,
    debounceMillis: Long,
) = SearchScrollPersistenceController(dispatcher, debounceMillis) { target ->
    persistSearchScrollState(
        index = target.state.firstVisibleItemIndex,
        offsetPx = target.state.firstVisibleItemOffsetPx,
        queryHash = target.queryHash,
    )
}

internal class SearchScrollPersistenceController(
    private val dispatcher: CoroutineDispatcher,
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

    suspend fun reset(queryHash: String, writeReset: suspend () -> Unit) = withContext(dispatcher) {
        val previous = synchronized(lock) {
            if (latest?.queryHash != queryHash) null else {
                latest = null
                committed = null
                activeJob.also { it?.cancel(); activeJob = null }
            }
        }
        previous?.join()
        commitMutex.withLock {
            synchronized(lock) { if (committed?.queryHash == queryHash) committed = null }
            writeReset()
        }
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            activeJob?.cancel(CancellationException("Search owner closed"))
            latest?.takeIf { target -> target != committed }
        }
        scope.launch {
            try {
                if (pending != null) commit(pending)
            } finally {
                scope.cancel(CancellationException("Search scroll scheduler closed"))
            }
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
