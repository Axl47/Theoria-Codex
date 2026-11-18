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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * One DataStore JSON file and whether it has opened. [awaitReady] opens it: success records [DurableStorePhase.READY],
 * and a file that refuses to open (written by a newer build, or unreadable and impossible to set aside) records
 * [DurableStorePhase.FAILED] with its reason and rethrows, so the startup gate shows its failure screen and nothing
 * on disk changes. [data] never fails because the store could not open: a collector that finds it unopenable waits
 * until a later [awaitReady] (the startup screen's Retry) opens it. Collectors started while the app is being built
 * therefore cannot crash the process, and every store that can fail must be awaited by the startup gate.
 */
internal class JsonDataStore<T>(private val store: DataStore<T>) : AsynchronousStore {
    private val status = MutableStateFlow(DurableStoreStatus())
    private val opening = Mutex()

    override val storageStatus: StateFlow<DurableStoreStatus> = status.asStateFlow()

    val data: Flow<T> = flow {
        try {
            awaitReady()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            status.first { current -> current.phase == DurableStorePhase.READY }
        }
        emitAll(store.data)
    }

    override suspend fun awaitReady() = opening.withLock {
        if (status.value.phase == DurableStorePhase.READY) return@withLock
        try {
            store.data.first()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            status.value = DurableStoreStatus(DurableStorePhase.FAILED, failure.message ?: failure::class.simpleName)
            throw failure
        }
        status.value = DurableStoreStatus(DurableStorePhase.READY)
    }

    /** Throws, like [awaitReady], when the store cannot open; nothing is written then. */
    suspend fun update(transform: suspend (T) -> T): T = store.updateDataInBackground(transform)
}

/**
 * Opens [file] as a [JsonDataStore] read and written by [JsonDataStoreSerializer]. An unreadable file is set aside by
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
): JsonDataStore<T> = JsonDataStore(
    DataStoreFactory.create(
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
    ),
)

internal fun ByteArray.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .toHexString()
}
