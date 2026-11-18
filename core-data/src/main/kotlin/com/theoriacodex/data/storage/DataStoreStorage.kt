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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

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
