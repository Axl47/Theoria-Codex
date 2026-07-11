package com.theoriacodex.data.storage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.lang.reflect.Type
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

interface AsynchronousStore {
    val storageStatus: StateFlow<DurableStoreStatus>
    suspend fun awaitReady()
}

data class LegacyImportProof(
    val sourceFileName: String = "",
    val sourceSchemaVersion: Int = 0,
    val destinationSchemaVersion: Int = 0,
    val sourceSha256: String = "",
    val sourceByteCount: Long = 0L,
    val importedCounts: Map<String, Int> = emptyMap(),
) {
    internal fun isValidFor(destinationSchema: Int): Boolean {
        return sourceFileName.isNotBlank() &&
            sourceSchemaVersion > 0 &&
            destinationSchemaVersion == destinationSchema &&
            sourceSha256.length == SHA_256_HEX_LENGTH &&
            sourceSha256.all { character -> character in '0'..'9' || character in 'a'..'f' } &&
            sourceByteCount >= 0L &&
            importedCounts.values.all { count -> count >= 0 }
    }
}

data class CorruptionRecovery(
    val reason: String,
    val backupPath: String?,
)

enum class DurableStorePhase {
    PENDING,
    READY,
    FAILED,
}

data class DurableStoreStatus(
    val phase: DurableStorePhase = DurableStorePhase.PENDING,
    val imports: List<LegacyImportProof> = emptyList(),
    val corruptionRecovery: CorruptionRecovery? = null,
    val failureReason: String? = null,
)

class UnsupportedStoreSchemaException(
    storeName: String,
    actual: Int,
    supported: Int,
) : IOException("$storeName schema $actual is newer than supported schema $supported")

internal class GsonDataStoreSerializer<T : Any>(
    private val storeName: String,
    override val defaultValue: T,
    private val type: Type,
    private val gson: Gson,
    private val validate: (T) -> Unit,
) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T {
        val json = input.reader(StandardCharsets.UTF_8).readText()
        if (json.isBlank()) {
            throw CorruptionException("$storeName is empty")
        }
        return try {
            val value = gson.fromJson<T>(json, type)
                ?: throw CorruptionException("$storeName did not contain a JSON value")
            validate(value)
            value
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unsupported: UnsupportedStoreSchemaException) {
            // A rollback must never replace data written by a newer app version.
            throw unsupported
        } catch (corruption: CorruptionException) {
            throw corruption
        } catch (parseFailure: JsonParseException) {
            throw CorruptionException("$storeName contains malformed JSON", parseFailure)
        } catch (invalid: NullPointerException) {
            throw CorruptionException("$storeName contains missing required values", invalid)
        } catch (invalid: IllegalArgumentException) {
            throw CorruptionException("$storeName failed schema validation", invalid)
        } catch (invalid: IllegalStateException) {
            throw CorruptionException("$storeName failed schema validation", invalid)
        }
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        validate(t)
        val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
        gson.toJson(t, type, writer)
        writer.flush()
    }
}

internal data class LegacyFileSnapshot(
    val logicalFileName: String,
    val actualFile: File,
    val bytes: ByteArray,
) {
    val sha256: String = bytes.sha256()
}

internal fun readLegacySnapshot(
    logicalFile: File,
    archiveFile: File,
): LegacyFileSnapshot? {
    for (candidate in listOf(logicalFile, archiveFile)) {
        if (!candidate.isFile) continue
        try {
            return LegacyFileSnapshot(
                logicalFileName = logicalFile.name,
                actualFile = candidate,
                bytes = candidate.readBytes(),
            )
        } catch (missing: java.io.FileNotFoundException) {
            // Another store can archive a shared legacy input concurrently. Try the archive.
            if (candidate == archiveFile) throw missing
        }
    }
    return null
}

/**
 * Retains a verified archive and removes the live legacy name only after the archive is durable.
 * Re-running this after a crash is safe, including the state where both files already exist.
 */
internal fun archiveLegacyFile(liveFile: File, archiveFile: File) {
    if (!liveFile.isFile) return
    val liveBytes = liveFile.readBytes()
    if (archiveFile.isFile) {
        check(archiveFile.readBytes().contentEquals(liveBytes)) {
            "Refusing to replace a different legacy archive at ${archiveFile.absolutePath}"
        }
        check(liveFile.delete() || !liveFile.exists()) {
            "Could not remove archived legacy file ${liveFile.absolutePath}"
        }
        return
    }

    archiveFile.parentFile?.mkdirs()
    val tempFile = archiveFile.resolveSibling(".${archiveFile.name}.${System.nanoTime()}.tmp")
    try {
        tempFile.writeBytes(liveBytes)
        check(tempFile.readBytes().contentEquals(liveBytes)) {
            "Legacy archive verification failed for ${liveFile.absolutePath}"
        }
        try {
            Files.move(
                tempFile.toPath(),
                archiveFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), archiveFile.toPath())
        }
        check(archiveFile.readBytes().contentEquals(liveBytes)) {
            "Legacy archive verification failed for ${archiveFile.absolutePath}"
        }
        check(liveFile.delete() || !liveFile.exists()) {
            "Could not remove archived legacy file ${liveFile.absolutePath}"
        }
    } finally {
        tempFile.delete()
    }
}

internal fun preserveCorruptFile(storeFile: File): String? {
    if (!storeFile.isFile) return null
    return runCatching {
        val backup = storeFile.resolveSibling("${storeFile.name}.corrupt")
        Files.copy(storeFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        backup.absolutePath
    }.getOrNull()
}

internal fun ByteArray.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private const val SHA_256_HEX_LENGTH = 64
