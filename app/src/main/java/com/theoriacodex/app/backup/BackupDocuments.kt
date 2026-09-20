package com.theoriacodex.app.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.theoriacodex.data.repository.PROFILE_BACKUP_MAX_BYTES
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The owner passes document IDs; stream and permission lifetimes stay in this gateway. */
internal interface BackupDocuments {
    suspend fun read(document: String): ByteArray
    suspend fun write(document: String, bytes: ByteArray)
    suspend fun discard(document: String)
}

internal class AndroidBackupDocuments(private val resolver: ContentResolver) : BackupDocuments {
    override suspend fun read(document: String): ByteArray {
        val input = resolver.openInputStream(Uri.parse(document))
            ?: error("Could not open the backup document.")
        return input.use { readBackupDocument(it) }
    }

    override suspend fun write(document: String, bytes: ByteArray) {
        val output = resolver.openOutputStream(Uri.parse(document), "wt")
            ?: error("Could not create the backup document.")
        output.use { it.write(bytes) }
    }

    override suspend fun discard(document: String) {
        runCatchingPreservingCancellation {
            DocumentsContract.deleteDocument(resolver, Uri.parse(document))
        }
    }
}

/** Reads at most one extra byte beyond the limit, including for streams without a size. */
internal suspend fun readBackupDocument(
    input: InputStream,
    maximumBytes: Int = PROFILE_BACKUP_MAX_BYTES,
): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer, 0, minOf(buffer.size, maximumBytes - output.size() + 1))
        if (count < 0) break
        require(output.size() + count <= maximumBytes) { "Backup files must be 32 MiB or smaller." }
        output.write(buffer, 0, count)
    }
    require(output.size() > 0) { "This backup file is empty." }
    return output.toByteArray()
}
