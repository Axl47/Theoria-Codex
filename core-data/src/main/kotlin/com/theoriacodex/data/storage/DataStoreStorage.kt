package com.theoriacodex.data.storage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

interface AsynchronousStore {
    val storageStatus: StateFlow<DurableStoreStatus>
    suspend fun awaitReady()
}

data class CorruptionRecovery(
    val reason: String,
    val backupPath: String?,
    val quarantinePath: String? = backupPath,
    val logicalStore: String = "",
    val logicalFile: String = "",
    val sha256: String = "",
    val byteCount: Long = 0L,
)

enum class DurableStorePhase {
    PENDING,
    READY,
    FAILED,
}

data class DurableStoreStatus(
    val phase: DurableStorePhase = DurableStorePhase.PENDING,
    val corruptionRecovery: CorruptionRecovery? = null,
    val failureReason: String? = null,
)

class UnsupportedStoreSchemaException(
    storeName: String,
    actual: Int,
    supported: Int,
) : IOException("$storeName schema $actual is newer than supported schema $supported")

/**
 * Reads and writes one DataStore JSON file through [DurableJson]. The file's top-level `schemaVersion` (absent means
 * [schemaVersion]) is checked before the typed decode: a newer one throws [UnsupportedStoreSchemaException], so a
 * rollback never replaces data a newer build wrote, whatever shape that data has. Malformed JSON, another schema
 * version, and a failed [validate] surface as [CorruptionException], which the store's corruption handler turns into
 * a reset with the original bytes kept aside.
 */
internal class JsonDataStoreSerializer<T : Any>(
    private val storeName: String,
    private val schemaVersion: Int,
    override val defaultValue: T,
    private val serializer: KSerializer<T>,
    private val validate: (T) -> Unit = {},
) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T {
        val json = input.readBytes().decodeToString()
        if (json.isBlank()) {
            throw CorruptionException("$storeName is empty")
        }
        try {
            val tree = DurableJson.parseToJsonElement(json)
            val stored = tree.storedVersion("schemaVersion") ?: schemaVersion
            if (stored > schemaVersion) {
                throw UnsupportedStoreSchemaException(storeName, stored, schemaVersion)
            }
            require(stored == schemaVersion) { "Unsupported $storeName schema $stored" }
            return DurableJson.decodeFromJsonElement(serializer, tree).also(validate)
        } catch (malformed: SerializationException) {
            throw CorruptionException("$storeName contains malformed JSON", malformed)
        } catch (invalid: IllegalArgumentException) {
            throw CorruptionException("$storeName failed schema validation", invalid)
        } catch (invalid: IllegalStateException) {
            throw CorruptionException("$storeName failed schema validation", invalid)
        }
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        validate(t)
        output.write(DurableJson.encodeToString(serializer, t).encodeToByteArray())
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
        .toHexString()
}
