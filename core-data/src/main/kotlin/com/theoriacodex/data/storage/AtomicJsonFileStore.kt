package com.theoriacodex.data.storage

import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
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
 * Verified recovery for legacy JSON reads and atomic whole-file replacement on an injected IO lane.
 * Store-specific schemas remain owned by callers. Writes compact JSON; older pretty-printed files still read.
 * Stores pass a kotlinx [KSerializer] (encoded with [DurableJson]); the Gson overloads remain for JSON trees and for
 * stores the serialization migration has not reached (.docs/exec/gson-to-kotlinx-serialization.html).
 */
class AtomicJsonFileStore private constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val gson: Gson,
    private val fileOperations: AtomicFileOperations,
) {
    constructor(
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        gson: Gson = Gson(),
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

    /** Gson reflective read, kept for stores that have not moved to kotlinx.serialization yet, and for JSON trees. */
    suspend fun <T> read(
        file: File,
        fallback: T,
        clazz: Class<T>,
        logicalStore: String = file.name,
        onRecovery: (CorruptionRecovery) -> Unit = {},
    ): T = readDecoded(file, fallback, logicalStore, onRecovery, isMalformed = { it is JsonParseException }) { text ->
        gson.fromJson(text, clazz)
    }

    /** kotlinx.serialization read under [DurableJson]; only a [SerializationException] counts as malformed. */
    suspend fun <T : Any> read(
        file: File,
        fallback: T,
        serializer: KSerializer<T>,
        logicalStore: String = file.name,
        onRecovery: (CorruptionRecovery) -> Unit = {},
    ): T = readDecoded(file, fallback, logicalStore, onRecovery, isMalformed = { it is SerializationException }) { text ->
        if (text.trim() == "null") null else DurableJson.decodeFromString(serializer, text)
    }

    /**
     * Reads [file], or returns [fallback] when it is missing. Bytes that are not UTF-8, blank, a JSON null, or that
     * [decode] rejects with an [isMalformed] failure are quarantined beside the file and reported through
     * [onRecovery]; any other failure propagates.
     */
    private suspend fun <T> readDecoded(
        file: File,
        fallback: T,
        logicalStore: String,
        onRecovery: (CorruptionRecovery) -> Unit,
        isMalformed: (RuntimeException) -> Boolean,
        decode: (String) -> T?,
    ): T {
        return withContext(ioDispatcher) {
            val path = file.toPath()
            if (Files.notExists(path)) {
                fallback
            } else if (!Files.exists(path)) {
                throw IOException("Could not determine whether ${file.absolutePath} is readable")
            } else {
                val bytes = fileOperations.readBytes(file)
                fun quarantine(reason: String) = recover(file, bytes, fallback, logicalStore, reason, onRecovery)
                val text = try {
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    return@withContext quarantine("${file.name} is not valid UTF-8 JSON")
                }
                if (text.isBlank()) return@withContext quarantine("${file.name} is empty")
                val decoded = try {
                    decode(text)
                } catch (error: RuntimeException) {
                    if (!isMalformed(error)) throw error
                    error.cancellationCause()?.let { cancellation -> throw cancellation }
                    return@withContext quarantine("${file.name} contains malformed JSON")
                }
                decoded ?: quarantine("${file.name} contains a null JSON value")
            }
        }
    }

    suspend inline fun <reified T> read(
        file: File,
        fallback: T,
        logicalStore: String = file.name,
        noinline onRecovery: (CorruptionRecovery) -> Unit = {},
    ): T {
        return read(
            file = file,
            fallback = fallback,
            clazz = T::class.java,
            logicalStore = logicalStore,
            onRecovery = onRecovery,
        )
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
            "Could not remove quarantined legacy file ${liveFile.absolutePath}"
        }
    }

    /** Gson reflective write, kept for stores that have not moved to kotlinx.serialization yet, and for JSON trees. */
    suspend fun <T> write(file: File, payload: T) {
        writePayload(file, maxBytes = null) { gson.toJson(payload) }
    }

    /** kotlinx.serialization write under [DurableJson]. */
    suspend fun <T> write(file: File, payload: T, serializer: KSerializer<T>) {
        writePayload(file, maxBytes = null) { DurableJson.encodeToString(serializer, payload) }
    }

    /** Flush a bounded operational intent before its caller starts irreversible work. */
    suspend fun <T> writeIntent(file: File, payload: T, maxBytes: Int) {
        require(maxBytes > 0)
        writePayload(file, maxBytes) { gson.toJson(payload) }
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

private fun Throwable.cancellationCause(): CancellationException? {
    return generateSequence(this as Throwable?) { error -> error.cause }
        .filterIsInstance<CancellationException>()
        .firstOrNull()
}
