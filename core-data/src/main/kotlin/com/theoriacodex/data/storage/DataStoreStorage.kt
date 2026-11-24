package com.theoriacodex.data.storage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
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

internal class JsonDataStoreSerializer<T : Any>(
    private val storeName: String,
    private val schemaVersion: Int,
    override val defaultValue: T,
    private val serializer: KSerializer<T>,
    private val validate: (T) -> Unit = {},
    private val oldestSchemaVersion: Int = schemaVersion,
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
            require(stored >= oldestSchemaVersion) { "Unsupported $storeName schema $stored" }
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

internal class JsonDataStore<T>(
    private val store: DataStore<T>,
    private val canonical: (T) -> T = { it },
) : AsynchronousStore, StateCell<T> {
    private val status = MutableStateFlow(DurableStoreStatus())
    private val opening = Mutex()

    override val storageStatus: StateFlow<DurableStoreStatus> = status.asStateFlow()

    override val data: Flow<T> = flow {
        runCatchingExceptionsPreservingCancellation {
            awaitReady()
        }.onFailure {
            status.first { current -> current.phase == DurableStorePhase.READY }
        }
        emitAll(store.data)
    }

    override suspend fun awaitReady() = opening.withLock {
        if (status.value.phase == DurableStorePhase.READY) return@withLock
        runCatchingExceptionsPreservingCancellation {
            store.data.first()
        }.onFailure { failure ->
            status.value = DurableStoreStatus(DurableStorePhase.FAILED, failure.message ?: failure::class.simpleName)
            throw failure
        }
        status.value = DurableStoreStatus(DurableStorePhase.READY)
    }

    suspend fun update(transform: suspend (T) -> T): T = store.updateDataInBackground { current ->
        val next = transform(current)
        if (next == current) current else canonical(next)
    }

    override suspend fun value(): T = data.first()

    override suspend fun <R> updateReturning(transform: (T) -> Pair<T, R>): R {
        var outcome: Pair<T, R>? = null
        update { current -> transform(current).also { outcome = it }.first }
        return checkNotNull(outcome).second
    }
}

internal fun <T : Any> openJsonDataStore(
    file: File,
    logicalStore: String,
    schemaVersion: Int,
    defaultValue: T,
    serializer: KSerializer<T>,
    scope: CoroutineScope,
    recoveries: FileStoreRecoveryRegistry,
    validate: (T) -> Unit = {},
): JsonDataStore<T> = openDecodedJsonDataStore(
    file, logicalStore, schemaVersion, defaultValue, serializer, scope, recoveries, validate,
    decode = { it },
    encode = { it },
)

internal fun <S : Any, T : Any> openDecodedJsonDataStore(
    file: File,
    logicalStore: String,
    schemaVersion: Int,
    defaultValue: S,
    serializer: KSerializer<S>,
    scope: CoroutineScope,
    recoveries: FileStoreRecoveryRegistry,
    validate: (S) -> Unit = {},
    decode: (S) -> T,
    encode: (T) -> S,
    oldestSchemaVersion: Int = schemaVersion,
    canonical: (T) -> T = { value -> decode(encode(value)) },
): JsonDataStore<T> {
    val fileSerializer = JsonDataStoreSerializer(file.name, schemaVersion, defaultValue, serializer, validate, oldestSchemaVersion)
    val decodedDefault = decode(defaultValue)
    return JsonDataStore(
        DataStoreFactory.create(
            serializer = object : Serializer<T> {
                override val defaultValue: T = decodedDefault
                override suspend fun readFrom(input: InputStream): T = decode(fileSerializer.readFrom(input))
                override suspend fun writeTo(t: T, output: OutputStream) = fileSerializer.writeTo(encode(t), output)
            },
            corruptionHandler = ReplaceFileCorruptionHandler { corruption ->
                val reason = corruption.message ?: "${file.name} was unreadable"
                recoveries.record(setAsideCorruptFile(file, file.readBytes(), logicalStore, reason))
                decodedDefault
            },
            scope = scope,
            produceFile = {
                recoveries.registerStore(logicalStore, file)
                file
            },
        ),
        canonical = canonical,
    )
}

internal fun ByteArray.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .toHexString()
}
