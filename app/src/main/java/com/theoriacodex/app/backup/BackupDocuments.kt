package com.theoriacodex.app.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.theoriacodex.data.repository.PROFILE_BACKUP_MAX_BYTES
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import java.io.InputStream

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
    return com.theoriacodex.data.repository.readBoundedDocument(input, maximumBytes)
}
