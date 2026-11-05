package com.theoriacodex.app.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri

internal class SharedPreferencesAutoBackupStore(context: Context) : AutoBackupStore {
    private val preferences = context.getSharedPreferences(AUTO_BACKUP_PREFERENCES, Context.MODE_PRIVATE)

    override fun read() = AutoBackupStatus(
        folderUri = preferences.getString(KEY_FOLDER, null),
        folderName = preferences.getString(KEY_FOLDER_NAME, null),
        lastSuccessEpochMs = preferences.getLong(KEY_LAST_SUCCESS, -1L).takeIf { it >= 0L },
        failure = preferences.getString(KEY_FAILURE, null),
    )

    override fun write(status: AutoBackupStatus) = preferences.edit {
        putString(KEY_FOLDER, status.folderUri)
        putString(KEY_FOLDER_NAME, status.folderName)
        putLong(KEY_LAST_SUCCESS, status.lastSuccessEpochMs ?: -1L)
        putString(KEY_FAILURE, status.failure)
    }
}

internal class AndroidAutoBackupFolder(private val resolver: ContentResolver, tree: Uri) : AutoBackupFolder {
    private val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    override fun names(): List<String> = documents().keys.toList()

    override fun write(name: String, bytes: ByteArray) {
        val part = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", name + AUTO_BACKUP_PART_SUFFIX)
            ?: error("Could not create the backup document.")
        try {
            checkNotNull(resolver.openOutputStream(part, "wt")).use { it.write(bytes) }
            val stored = checkNotNull(resolver.openInputStream(part)).use { it.readBytes() }
            check(stored.contentEquals(bytes)) { "The saved backup did not match." }
            checkNotNull(DocumentsContract.renameDocument(resolver, part, name)) { "Could not finish the backup document." }
        } catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, part) }
            throw failure
        }
    }

    override fun delete(name: String) {
        documents()[name]?.let { DocumentsContract.deleteDocument(resolver, it) }
    }

    private fun documents(): Map<String, Uri> {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return buildMap {
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    put(cursor.getString(1), DocumentsContract.buildDocumentUriUsingTree(children, cursor.getString(0)))
                }
            }
        }
    }
}

/** Keeps the system's grant to the picked folder across restarts and returns its display name. */
internal fun ContentResolver.adoptAutoBackupFolder(tree: Uri): String? {
    takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    return runCatching {
        query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}

internal fun ContentResolver.releaseAutoBackupFolder(folderUri: String?) {
    if (folderUri == null) return
    runCatching {
        releasePersistableUriPermission(folderUri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}

internal const val AUTO_BACKUP_PREFERENCES = "theoria_auto_backup"
private const val KEY_FOLDER = "folder"
private const val KEY_FOLDER_NAME = "folder_name"
private const val KEY_LAST_SUCCESS = "last_success"
private const val KEY_FAILURE = "failure"
