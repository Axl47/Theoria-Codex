package com.theoriacodex.data.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * Verified recovery for JSON reads and atomic whole-file replacement on an injected IO lane.
 * Store-specific schemas remain owned by callers, as kotlinx [KSerializer]s encoded with [DurableJson].
 * Writes compact JSON; older pretty-printed files still read.
 */
class AtomicJsonFileStore internal constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val fileOperations: AtomicFileOperations,
) {
    constructor(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : this(ioDispatcher, DefaultAtomicFileOperations)

    /**
     * Reads [file], or returns [fallback] when it is missing. Bytes that are not UTF-8, blank, a JSON null, or that
     * [serializer] rejects with a [SerializationException] are quarantined beside the file and reported through
     * [onRecovery]; any other failure, such as an [IllegalArgumentException] from a record's own checks, propagates.
     */
    suspend fun <T : Any> read(
        file: File,
        fallback: T,
        serializer: KSerializer<T>,
        logicalStore: String = file.name,
        onRecovery: (CorruptionRecovery) -> Unit = {},
    ): T {
        return withContext(ioDispatcher) {
            val path = file.toPath()
            if (Files.notExists(path)) return@withContext fallback
            if (!Files.exists(path)) throw IOException("Could not determine whether ${file.absolutePath} is readable")
            val bytes = fileOperations.readBytes(file)
            fun quarantine(reason: String) = recover(file, bytes, fallback, logicalStore, reason, onRecovery)
            val text = bytes.strictUtf8OrNull()
                ?: return@withContext quarantine("${file.name} is not valid UTF-8 JSON")
            if (text.isBlank()) return@withContext quarantine("${file.name} is empty")
            if (text.trim() == "null") return@withContext quarantine("${file.name} contains a null JSON value")
            try {
                DurableJson.decodeFromString(serializer, text)
            } catch (error: SerializationException) {
                error.cancellationCause()?.let { cancellation -> throw cancellation }
                quarantine("${file.name} contains malformed JSON")
            }
        }
    }

    private fun <T> recover(
        file: File,
        bytes: ByteArray,
        fallback: T,
        logicalStore: String,
        reason: String,
        onRecovery: (CorruptionRecovery) -> Unit,
    ): T {
        val sha256 = bytes.sha256()
        val quarantine = file.resolveSibling(
            "${file.name}.corrupt-${bytes.size}-$sha256",
        )
        preserveVerified(file, quarantine, bytes)
        onRecovery(
            CorruptionRecovery(
                reason = reason,
                backupPath = quarantine.absolutePath,
                logicalStore = logicalStore,
                logicalFile = file.name,
                sha256 = sha256,
                byteCount = bytes.size.toLong(),
            )
        )
        return fallback
    }

    private fun preserveVerified(liveFile: File, quarantineFile: File, bytes: ByteArray) {
        if (fileOperations.exists(quarantineFile)) {
            check(fileOperations.readBytes(quarantineFile).contentEquals(bytes)) {
                "Refusing to replace a different quarantine at ${quarantineFile.absolutePath}"
            }
        } else {
            quarantineFile.parentFile?.mkdirs()
            val tempFile = fileOperations.createTempFile(quarantineFile)
            try {
                fileOperations.writeAndSync(tempFile, bytes)
                check(fileOperations.readBytes(tempFile).contentEquals(bytes)) {
                    "Quarantine verification failed for ${liveFile.absolutePath}"
                }
                try {
                    fileOperations.moveWithoutReplace(tempFile, quarantineFile)
                } catch (_: FileAlreadyExistsException) {
                    check(fileOperations.readBytes(quarantineFile).contentEquals(bytes)) {
                        "Refusing to replace a different quarantine at ${quarantineFile.absolutePath}"
                    }
                }
            } finally {
                if (fileOperations.exists(tempFile)) {
                    fileOperations.delete(tempFile)
                }
            }
        }
        check(fileOperations.readBytes(quarantineFile).contentEquals(bytes)) {
            "Quarantine verification failed for ${quarantineFile.absolutePath}"
        }
        check(fileOperations.delete(liveFile) || !fileOperations.exists(liveFile)) {
            "Could not remove quarantined file ${liveFile.absolutePath}"
        }
    }

    /** Atomically replaces [file] with [payload] encoded under [DurableJson]. */
    suspend fun <T> write(file: File, payload: T, serializer: KSerializer<T>) {
        writePayload(file, maxBytes = null) { DurableJson.encodeToString(serializer, payload) }
    }

    /** Flush a bounded operational intent, and its new directories, before its caller starts irreversible work. */
    suspend fun <T> writeIntent(file: File, payload: T, serializer: KSerializer<T>, maxBytes: Int) {
        require(maxBytes > 0)
        writePayload(file, maxBytes) { DurableJson.encodeToString(serializer, payload) }
    }

    private suspend fun writePayload(file: File, maxBytes: Int?, encode: () -> String) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            withContext(ioDispatcher) {
                val parent = file.absoluteFile.parentFile ?: File(".")
                val newParents = if (maxBytes == null) emptyList() else generateSequence(parent) { it.parentFile }
                    .takeWhile { !it.exists() }.toList()
                parent.mkdirs()
                val tempFile = fileOperations.createTempFile(file)
                try {
                    writeTempPayload(tempFile, encode(), maxBytes)
                    try {
                        fileOperations.moveAtomically(tempFile, file)
                    } catch (_: AtomicMoveNotSupportedException) {
                        fileOperations.replace(tempFile, file)
                    }
                    if (maxBytes != null) syncIntentDirectories(parent, newParents)
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
            }
        }
    }

    private fun syncIntentDirectories(parent: File, newParents: List<File>) {
        fileOperations.syncDirectory(parent)
        newParents.forEach { fileOperations.syncDirectory(it.parentFile ?: File(".")) }
    }

    private fun writeTempPayload(file: File, text: String, maxBytes: Int?) {
        if (maxBytes == null) file.writeText(text)
        else {
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= maxBytes) { "${file.name} exceeds its intent size limit" }
            fileOperations.writeAndSync(file, bytes)
        }
    }
}

internal interface AtomicFileOperations {
    fun createTempFile(target: File): File
    fun moveAtomically(tempFile: File, target: File)
    fun replace(tempFile: File, target: File)
    fun readBytes(file: File): ByteArray = file.readBytes()
    fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }
    fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), READ).use { it.force(true) }
    }
    fun moveWithoutReplace(tempFile: File, target: File) {
        Files.move(tempFile.toPath(), target.toPath())
    }
    fun exists(file: File): Boolean = file.exists()
    fun delete(file: File): Boolean = Files.deleteIfExists(file.toPath())
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

private fun ByteArray.strictUtf8OrNull(): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

private fun Throwable.cancellationCause(): CancellationException? {
    return generateSequence(this as Throwable?) { error -> error.cause }
        .filterIsInstance<CancellationException>()
        .firstOrNull()
}
