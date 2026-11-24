package com.theoriacodex.data.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface StateCell<T> {
    val data: Flow<T>

    suspend fun value(): T

    suspend fun <R> updateReturning(transform: (T) -> Pair<T, R>): R
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
    }
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
