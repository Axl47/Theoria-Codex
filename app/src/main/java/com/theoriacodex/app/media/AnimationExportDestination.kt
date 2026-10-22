package com.theoriacodex.app.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** The queue journals its marker before preparation; both destinations preserve that identity. */
internal class AnimationExportDestination(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun findCompleted(marker: String): Uri? = findCompletedKey(markerKey(marker))

    fun save(fileName: String, marker: String?, encode: (FileDescriptor) -> Unit): Uri {
        val key = marker?.let(::markerKey)
        key?.let { findCompletedKey(it)?.let { uri -> return uri } }
        return try {
            savePublic(fileName, key, encode)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // A publish may have committed before its caller lost the acknowledgement.
            key?.let { findCompletedKey(it)?.let { uri -> return uri } }
            savePrivate(fileName, key, encode)
        }
    }

    fun deleteCompleted(marker: String) {
        val key = markerKey(marker)
        val private = privateFile("", key)
        if (private.exists() && !private.delete()) throw IOException("Could not delete animation export")
        val uri = findRow(key, complete = true) ?: return
        if (resolver.delete(uri, null, null) != 1) throw IOException("Could not delete animation export")
    }

    private fun findCompletedKey(key: String): Uri? {
        val file = privateFile("", key)
        if (file.isFile && file.length() > 0) return Uri.fromFile(file)
        findRow(key, complete = true)?.let { uri ->
            resolver.openFileDescriptor(uri, "r")?.use {
                if (it.statSize <= 0) throw IOException("Completed animation export is unreadable")
            } ?: throw IOException("Completed animation export is unavailable")
            return uri
        }
        return null
    }

    private fun findRow(key: String, complete: Boolean): Uri? {
        val selection = "${MediaStore.Video.VideoColumns.DESCRIPTION} = ?"
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val arguments = arrayOf(description(key, complete))
        val cursor = resolver.query(collection, projection, Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arguments)
            if (!complete) putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }, null) ?: throw IOException("Could not read animation export destinations")
        return cursor.use {
            if (cursor.count > 1) throw IOException("Animation export identity has multiple destinations")
            if (cursor.moveToFirst()) ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                cursor.getLong(0)) else null
        }
    }

    private fun savePublic(fileName: String, key: String?, encode: (FileDescriptor) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            key?.let { put(MediaStore.Video.VideoColumns.DESCRIPTION, description(it, complete = false)) }
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TheoriaCodex")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = key?.let { findRow(it, complete = false) }
            ?: resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create animation export")
        try {
            resolver.openFileDescriptor(destination, "rwt")?.use {
                encode(it.fileDescriptor)
                it.fileDescriptor.sync()
            } ?: throw IOException("Could not open animation export")
            val publish = ContentValues().apply {
                key?.let { put(MediaStore.Video.VideoColumns.DESCRIPTION, description(it, complete = true)) }
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (publish.size() > 0 && resolver.update(destination, publish, null, null) != 1) {
                throw IOException("Could not publish animation export")
            }
            return destination
        } catch (error: Exception) {
            if (key == null || findRow(key, complete = false) == destination) resolver.delete(destination, null, null)
            throw error
        }
    }

    private fun privateFile(fileName: String, key: String?): File = File(
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "TheoriaCodex"),
        key?.let { "ugoira-export-$it.mp4" } ?: fileName,
    )

    private fun savePrivate(fileName: String, key: String?, encode: (FileDescriptor) -> Unit): Uri {
        val destination = privateFile(fileName, key)
        val directory = requireNotNull(destination.parentFile)
        val created = !directory.exists()
        directory.mkdirs()
        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        var published = false
        try {
            encode(output.fd)
            output.fd.sync()
            atomic.finishWrite(output)
            published = true
            if (!destination.isFile || destination.length() <= 0) throw IOException("Could not publish private animation export")
            FileChannel.open(directory.toPath(), READ).use { it.force(true) }
            if (created) directory.parentFile?.let { parent ->
                FileChannel.open(parent.toPath(), READ).use { it.force(true) }
            }
            return Uri.fromFile(destination)
        } catch (error: Exception) {
            if (!published) atomic.failWrite(output)
            throw error
        }
    }

    private fun markerKey(marker: String): String = MessageDigest.getInstance("SHA-256")
        .digest(marker.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun description(key: String, complete: Boolean): String =
        "theoria-export:$key:${if (complete) "complete" else "pending"}"
}
