package com.theoriacodex.app.codex.transfer

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_RETAINED_EXPORTS = 16
private const val EXPORT_RETENTION_MS = 24L * 60 * 60 * 1000

/** Document providers and exported files can block independently of repository suspension. */
internal suspend fun readBoundedTextDocument(maxBytes: Int, open: () -> InputStream?): String? = withContext(Dispatchers.IO) {
    open()?.use { input ->
        com.theoriacodex.data.repository.decodeDocumentUtf8(
            com.theoriacodex.data.repository.readBoundedDocument(input, maxBytes),
        )
    }
}

/** Saves a copy in the public Downloads/TheoriaCodex folder; Android renames it if that name is taken. */
internal suspend fun saveExportToDownloads(resolver: ContentResolver, document: ExportDocument, mimeType: String): Unit = withContext(Dispatchers.IO) {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, document.fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/TheoriaCodex")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val destination = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "Could not create the download" }
    try {
        checkNotNull(resolver.openOutputStream(destination)) { "Could not open the download" }
            .use { it.write(document.text.toByteArray(Charsets.UTF_8)) }
        val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        check(resolver.update(destination, published, null, null) == 1) { "Could not finish the download" }
    } catch (error: Exception) {
        resolver.delete(destination, null, null)
        throw error
    }
}

/**
 * Gives each share its own folder. A receiver's read grant covers the file, so reusing a path would let an
 * earlier recipient read whatever was shared next. Old shares are removed once no receiver should still need them.
 */
internal suspend fun writeExportDocument(
    directory: File,
    document: ExportDocument,
    now: () -> Long = System::currentTimeMillis,
): File = withContext(Dispatchers.IO) {
    check(directory.isDirectory || directory.mkdirs()) { "Could not create export directory" }
    directory.listFiles().orEmpty().sortedByDescending(File::lastModified)
        .filterIndexed { index, entry -> index >= MAX_RETAINED_EXPORTS - 1 || now() - entry.lastModified() > EXPORT_RETENTION_MS }
        .forEach(File::deleteRecursively)
    val share = directory.resolve(UUID.randomUUID().toString())
    check(share.mkdirs()) { "Could not create export directory" }
    share.resolve(document.fileName).also { it.writeText(document.text) }
}
