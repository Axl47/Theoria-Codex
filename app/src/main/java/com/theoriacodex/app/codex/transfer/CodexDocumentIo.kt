package com.theoriacodex.app.codex.transfer

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Document providers and exported files can block independently of repository suspension. */
internal suspend fun readCodexDocument(open: () -> InputStream?): String? = withContext(Dispatchers.IO) {
    open()?.bufferedReader()?.use { it.readText() }
}

internal suspend fun writeCodexDocument(directory: File, payload: CodexExportPayload): File =
    withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create export directory" }
        directory.resolve(payload.fileName).also { it.writeText(payload.json) }
    }
