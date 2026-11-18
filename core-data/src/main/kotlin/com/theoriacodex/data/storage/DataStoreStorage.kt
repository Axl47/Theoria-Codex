package com.theoriacodex.data.storage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
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
 * version, and a failed [validate] surface as [CorruptionException], which [openJsonDataStore]'s corruption handler turns
 * into a reset with the original bytes set aside.
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

/**
 * Opens [file] as a DataStore read and written by [JsonDataStoreSerializer]. An unreadable file is set aside by
 * [setAsideCorruptFile], recorded in [recoveries] under [logicalStore], and replaced by [defaultValue]. When it cannot
 * be set aside, the error propagates and the store fails to open rather than overwrite the only copy of the user's
 * data. DataStore then finds no file and starts from [defaultValue]; the replacement file is written by the first
 * update. Files set aside in an earlier process are registered on first access, which keeps construction free of I/O.
 */
internal fun <T : Any> openJsonDataStore(
    file: File,
    logicalStore: String,
    schemaVersion: Int,
    defaultValue: T,
    serializer: KSerializer<T>,
    scope: CoroutineScope,
    recoveries: FileStoreRecoveryRegistry,
    validate: (T) -> Unit = {},
): DataStore<T> = DataStoreFactory.create(
    serializer = JsonDataStoreSerializer(file.name, schemaVersion, defaultValue, serializer, validate),
    corruptionHandler = ReplaceFileCorruptionHandler { corruption ->
        val reason = corruption.message ?: "${file.name} was unreadable"
        recoveries.record(setAsideCorruptFile(file, file.readBytes(), logicalStore, reason))
        defaultValue
    },
    scope = scope,
    produceFile = {
        recoveries.registerStore(logicalStore, file)
        file
    },
)

internal fun ByteArray.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .toHexString()
}
