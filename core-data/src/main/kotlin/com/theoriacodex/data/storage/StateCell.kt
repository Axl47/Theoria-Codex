package com.theoriacodex.data.storage

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface StateCell<T> {
    val data: Flow<T>

    suspend fun value(): T

    suspend fun <R> updateReturning(transform: (T) -> Pair<T, R>): R

    suspend fun flush() = Unit

    suspend fun replace(value: T) = update { value }
}

internal suspend fun <T> StateCell<T>.update(transform: (T) -> T) {
    updateReturning { current -> transform(current) to Unit }
}

internal fun <S, T> StateCell<S>.focus(get: (S) -> T, put: (S, T) -> S): StateCell<T> {
    val whole = this
    return object : StateCell<T> {
        override val data: Flow<T> = whole.data.map(get).distinctUntilChanged()

        override suspend fun value(): T = get(whole.value())

        override suspend fun <R> updateReturning(transform: (T) -> Pair<T, R>): R = whole.updateReturning { stored ->
            val part = get(stored)
            val (next, result) = transform(part)
            (if (next == part) stored else put(stored, next)) to result
        }

        override suspend fun flush() = whole.flush()
    }
}

internal const val WRITE_BEHIND_RETRIES = 3

class WriteBehind(internal val scope: CoroutineScope, internal val delay: Duration = WRITE_BEHIND_DELAY) {
    companion object {
        fun flushOnly(scope: CoroutineScope) = WriteBehind(scope, Duration.INFINITE)
    }
}

internal val WRITE_BEHIND_DELAY = 2.seconds

internal fun <T : Any> StateCell<T>.writeBehind(config: WriteBehind?): StateCell<T> =
    config?.let { WriteBehindStateCell(this, it.scope, it.delay) } ?: this

private class WriteBehindStateCell<T : Any>(
    private val durable: StateCell<T>,
    private val scope: CoroutineScope,
    private val writeDelay: Duration,
) : StateCell<T> {
    private val mutex = Mutex()
    private val flushing = Mutex()
    private val queue = Dispatchers.Default.limitedParallelism(1)
    private val state = MutableStateFlow<T?>(null)
    private var revision = 0L
    private var persistedRevision = 0L
    private var scheduled: Job? = null

    override val data: Flow<T> = flow {
        if (state.value == null) value()
        emitAll(state.filterNotNull())
    }

    override suspend fun value(): T = withContext(queue) { mutex.withLock { loaded() } }

    override suspend fun <R> updateReturning(transform: (T) -> Pair<T, R>): R = withContext(queue + NonCancellable) {
        mutex.withLock { applying(transform) }
    }

    private suspend fun <R> applying(transform: (T) -> Pair<T, R>): R {
        val current = loaded()
        val (next, result) = transform(current)
        if (next != current) {
            state.value = next
            revision++
            schedule(attempt = 0)
        }
        return result
    }

    override suspend fun flush() = withContext(queue) { flushOnce() }

    private suspend fun flushOnce() = flushing.withLock {
        val (latest, latestRevision) = mutex.withLock { state.value to revision }
        if (latest == null || latestRevision == persistedRevision) return@withLock
        durable.replace(latest)
        mutex.withLock { persistedRevision = maxOf(persistedRevision, latestRevision) }
    }

    private fun schedule(attempt: Int) {
        if (scheduled?.isActive != true) scheduled = scope.launch { scheduledFlush(attempt) }
    }

    private suspend fun scheduledFlush(attempt: Int) {
        delay(writeDelay)
        mutex.withLock { scheduled = null }
        val failed = runCatchingPreservingCancellation { flushOnce() }.isFailure
        if (failed && attempt < WRITE_BEHIND_RETRIES) mutex.withLock { schedule(attempt + 1) }
    }

    private suspend fun loaded(): T = state.value ?: durable.value().also { state.value = it }
}

internal class MemoryStateCell<T>(initial: T) : StateCell<T> {
    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)

    override val data: Flow<T> = state.asStateFlow()

    override suspend fun value(): T = mutex.withLock { state.value }

    override suspend fun <R> updateReturning(transform: (T) -> Pair<T, R>): R = mutex.withLock {
        val (next, result) = transform(state.value)
        state.value = next
        result
    }
}

internal class FileStateCell<T : Any> private constructor(
    initial: T?,
    private val load: suspend () -> T,
    private val write: suspend (T) -> Unit,
) : StateCell<T> {
    constructor(initial: T, write: suspend (T) -> Unit) : this(initial, { initial }, write)
    constructor(load: suspend () -> T, write: suspend (T) -> Unit) : this(null, load, write)

    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)

    override val data: Flow<T> = flow {
        if (state.value == null) value()
        emitAll(state.filterNotNull())
    }

    override suspend fun value(): T = mutex.withLock { loaded() }

    override suspend fun <R> updateReturning(transform: (T) -> Pair<T, R>): R = mutex.withLock {
        val current = loaded()
        val (next, result) = transform(current)
        if (next != current) {
            write(next)
            state.value = next
        }
        result
    }

    private suspend fun loaded(): T = state.value ?: load().also { state.value = it }
}
