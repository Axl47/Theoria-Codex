package com.theoriacodex.app.codex.transfer

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Document providers and exported files can block independently of repository suspension. */
internal suspend fun readBoundedTextDocument(maxBytes: Int, open: () -> InputStream?): String? = withContext(Dispatchers.IO) {
    open()?.use { input ->
        com.theoriacodex.data.repository.decodeDocumentUtf8(
            com.theoriacodex.data.repository.readBoundedDocument(input, maxBytes),
        )
    }
}

internal suspend fun writeExportDocument(directory: File, document: ExportDocument): File =
    withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create export directory" }
        directory.resolve(document.fileName).also { it.writeText(document.text) }
    }
