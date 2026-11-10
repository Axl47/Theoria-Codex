package com.theoriacodex.app.codex.transfer

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
