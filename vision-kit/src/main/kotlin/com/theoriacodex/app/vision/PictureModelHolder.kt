package com.theoriacodex.app.vision

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps a [PictureModel] loaded only while pictures arrive: [open] loads it on first use (about 350 MB while loaded),
 * one picture runs at a time, and it closes [idleMs] after the last one, or at once on [release] (memory pressure, the
 * model deleted). Work queues on a fair lock, so the deck's look-ahead waits at most one background picture.
 */
class PictureModelHolder(
    private val scope: CoroutineScope,
    private val idleMs: Long = IDLE_MS,
    private val open: () -> PictureModel,
) {
    private val lock = Mutex()
    private var model: PictureModel? = null
    private var closing: Job? = null

    /** Runs [block] with the loaded model, loading it first when needed. Throws [LoadFailed] or what [block] throws. */
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

    /** Closes the model now; the next [use] loads it again. */
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

    /** The model could not be loaded (as opposed to failing on one picture). */
    class LoadFailed(cause: Throwable) : Exception("The picture model could not be loaded", cause)

    private fun closeModel() {
        model?.close()
        model = null
    }

    private companion object {
        const val IDLE_MS = 60_000L
    }
}
