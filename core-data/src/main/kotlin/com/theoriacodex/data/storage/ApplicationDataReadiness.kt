package com.theoriacodex.data.storage

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ApplicationDataState<out T> {
    data object NotStarted : ApplicationDataState<Nothing>
    data object Loading : ApplicationDataState<Nothing>
    data class Ready<T>(val value: T) : ApplicationDataState<T>
    data class Failed(val cause: Throwable) : ApplicationDataState<Nothing>
}

class ApplicationDataReadiness<T>(
    private val applicationScope: CoroutineScope,
    private val initializationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val initialize: suspend () -> T,
) {
    private sealed interface AttemptResult<out T> {
        data class Success<T>(val value: T) : AttemptResult<T>
        data class Failure(val cause: Throwable) : AttemptResult<Nothing>
    }

    private val lock = Any()
    private val mutableState = MutableStateFlow<ApplicationDataState<T>>(ApplicationDataState.NotStarted)
    private var attempt: Deferred<AttemptResult<T>>? = null

    val state: StateFlow<ApplicationDataState<T>> = mutableState.asStateFlow()

    fun start() {
        synchronized(lock) {
            if (attempt != null || mutableState.value is ApplicationDataState.Ready ||
                mutableState.value is ApplicationDataState.Failed
            ) {
                return
            }
            attempt = newAttempt()
        }
    }

    suspend fun awaitReady(): T {
        start()
        return await(requireNotNull(synchronized(lock) { attempt }))
    }

    suspend fun retry(): T {
        val currentAttempt = synchronized(lock) {
            when (val current = mutableState.value) {
                is ApplicationDataState.Ready -> return current.value
                ApplicationDataState.Loading -> requireNotNull(attempt)
                ApplicationDataState.NotStarted -> newAttempt().also { created -> attempt = created }
                is ApplicationDataState.Failed -> newAttempt().also { created -> attempt = created }
            }
        }
        return await(currentAttempt)
    }

    private fun newAttempt(): Deferred<AttemptResult<T>> {
        mutableState.value = ApplicationDataState.Loading
        return applicationScope.async(initializationDispatcher) {
            runCatchingPreservingCancellation {
                val value = initialize()
                mutableState.value = ApplicationDataState.Ready(value)
                AttemptResult.Success(value)
            }.getOrElse { failure ->
                com.theoriacodex.domain.diagnostics.Diagnostics.report("startup", "Saved data could not be opened", failure)
                mutableState.value = ApplicationDataState.Failed(failure)
                AttemptResult.Failure(failure)
            }
        }
    }

    private suspend fun await(currentAttempt: Deferred<AttemptResult<T>>): T {
        return when (val result = currentAttempt.await()) {
            is AttemptResult.Success -> result.value
            is AttemptResult.Failure -> throw result.cause
        }
    }
}
