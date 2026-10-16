package com.theoriacodex.app.media

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.IOException

internal data class DownloadedPostFile(val uri: Uri, val mime: String?)

/** Resolve tracked Android destinations; never persist or guess a provider's media path. */
internal class PostDownloadFiles(private val context: Context) {
    private val manager get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    fun find(item: PostDownloadItem): DownloadedPostFile? {
        val nativeId = item.downloadId
        if (nativeId != null) {
            val downloads = manager ?: return null
            val uri = downloads.getUriForDownloadedFile(nativeId) ?: existingDestination(item) ?: return null
            return readable(uri, downloads.getMimeTypeForDownloadedFile(nativeId))
        }
        val exported = AnimationExportDestination(context).findCompleted(item.request.marker) ?: return null
        return readable(exported, "video/mp4")
    }

    private fun readable(uri: Uri, mime: String?): DownloadedPostFile? {
        val contentUri = if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            if (!file.isFile || !file.canRead()) return null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else uri
        context.contentResolver.openFileDescriptor(contentUri, "r")?.use { descriptor ->
            if (descriptor.statSize == 0L) return null
        } ?: return null
        return DownloadedPostFile(contentUri, mime ?: context.contentResolver.getType(contentUri))
    }

    /** Existing-file collisions may point at a file without a successful native transfer. */
    private fun existingDestination(item: PostDownloadItem): Uri? {
        if (item.status != PostDownloadStatus.ALREADY_EXISTS && item.status != PostDownloadStatus.FAILED) return null
        val id = item.downloadId ?: return null
        val downloads = manager ?: return null
        return downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val location = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: return@use null
            val uri = location.toUri()
            if (uri.scheme != "file") return@use null
            val file = File(uri.path ?: return@use null).canonicalFile
            val roots = listOfNotNull(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TheoriaCodex"),
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            )
            uri.takeIf { file.isFile && roots.any { root -> file.toPath().startsWith(root.canonicalFile.toPath()) } }
        }
    }

    fun delete(item: PostDownloadItem) {
        val id = item.downloadId
        if (id == null) {
            AnimationExportDestination(context).deleteCompleted(item.request.marker)
            return
        }
        val downloads = manager ?: throw IOException("Downloads are unavailable")
        val existing = existingDestination(item)
        if (existing != null) {
            val file = File(requireNotNull(existing.path))
            if (file.exists() && !file.delete()) throw IOException("Could not delete downloaded file")
        }
        downloads.remove(id)
    }
}

/** A missing file or missing Android viewer falls back to the canonical post inside the app. */
internal fun openDownloadedPostFile(context: Context, file: DownloadedPostFile): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(file.uri, file.mime ?: "application/octet-stream")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}.isSuccess

/** Cancellation stays cancellation; unavailable files and missing Android handlers open the post. */
internal suspend fun openPostDownload(
    item: PostDownloadItem,
    findFile: suspend (PostDownloadItem) -> DownloadedPostFile?,
    openFile: (DownloadedPostFile) -> Boolean,
    openPost: suspend (PostDownloadItem) -> Unit,
) {
    val file = com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation { findFile(item) }.getOrNull()
    if (file == null || !openFile(file)) openPost(item)
}
