package com.theoriacodex.app.vision

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PictureModelHolder(
    private val scope: CoroutineScope,
    private val idleMs: Long = IDLE_MS,
    private val open: () -> PictureModel,
) {
    private val lock = Mutex()
    private var model: PictureModel? = null
    private var closing: Job? = null

    suspend fun <T> use(block: (PictureModel) -> T): T = lock.withLock {
        closing?.cancel()
        try {
            withContext(Dispatchers.Default) { block(model ?: load()) }
        } finally {
            closing = scope.launch {
                delay(idleMs)
                lock.withLock { closeModel() }
            }
        }
    }

    suspend fun release() {
        lock.withLock {
            closing?.cancel()
            closeModel()
        }
    }

    private fun load(): PictureModel = try {
        open().also { model = it }
    } catch (error: Throwable) {
        throw LoadFailed(error)
    }

    class LoadFailed(cause: Throwable) : Exception("The picture model could not be loaded", cause)

    private fun closeModel() {
        model?.close()
        model = null
    }

    private companion object {
        const val IDLE_MS = 60_000L
    }
}
