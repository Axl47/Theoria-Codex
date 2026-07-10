package com.theoriacodex.data.storage

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Tolerant JSON reads and atomic whole-file replacement on an injected IO lane.
 * Store-specific schemas remain owned by callers.
 */
class AtomicJsonFileStore private constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val gson: Gson,
    private val fileOperations: AtomicFileOperations,
) {
    constructor(
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        gson: Gson = GsonBuilder().setPrettyPrinting().create(),
    ) : this(
        ioDispatcher = ioDispatcher,
        gson = gson,
        fileOperations = DefaultAtomicFileOperations,
    )

    internal constructor(
        ioDispatcher: CoroutineDispatcher,
        gson: Gson,
        fileOperations: AtomicFileOperations,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(
        ioDispatcher = ioDispatcher,
        gson = gson,
        fileOperations = fileOperations,
    )

    suspend fun <T> read(file: File, fallback: T, clazz: Class<T>): T {
        return withContext(ioDispatcher) {
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                fallback
            } else {
                try {
                    gson.fromJson(file.readText(), clazz) ?: fallback
                } catch (_: IOException) {
                    fallback
                } catch (error: JsonParseException) {
                    error.cancellationCause()?.let { cancellation -> throw cancellation }
                    fallback
                }
            }
        }
    }

    suspend inline fun <reified T> read(file: File, fallback: T): T {
        return read(file = file, fallback = fallback, clazz = T::class.java)
    }

    suspend fun <T> write(file: File, payload: T) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            withContext(ioDispatcher) {
                val parent = file.parentFile
                parent?.mkdirs()
                val tempFile = fileOperations.createTempFile(file)
                try {
                    tempFile.writeText(gson.toJson(payload))
                    try {
                        fileOperations.moveAtomically(tempFile, file)
                    } catch (_: AtomicMoveNotSupportedException) {
                        fileOperations.replace(tempFile, file)
                    }
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
            }
        }
    }
}

internal interface AtomicFileOperations {
    fun createTempFile(target: File): File
    fun moveAtomically(tempFile: File, target: File)
    fun replace(tempFile: File, target: File)
}

private object DefaultAtomicFileOperations : AtomicFileOperations {
    override fun createTempFile(target: File): File {
        return File.createTempFile(
            "${target.name}.",
            ".tmp",
            target.parentFile ?: File("."),
        )
    }

    override fun moveAtomically(tempFile: File, target: File) {
        Files.move(tempFile.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    override fun replace(tempFile: File, target: File) {
        Files.move(tempFile.toPath(), target.toPath(), REPLACE_EXISTING)
    }
}

internal suspend inline fun <State, Result> mutateAndPersistWithRollback(
    snapshot: () -> State,
    restore: (State) -> Unit,
    mutate: () -> Result,
    persist: () -> Unit,
): Result {
    currentCoroutineContext().ensureActive()
    val previous = snapshot()
    return try {
        val result = mutate()
        persist()
        result
    } catch (failure: Throwable) {
        restore(previous)
        throw failure
    }
}

private fun Throwable.cancellationCause(): CancellationException? {
    return generateSequence(this as Throwable?) { error -> error.cause }
        .filterIsInstance<CancellationException>()
        .firstOrNull()
}
