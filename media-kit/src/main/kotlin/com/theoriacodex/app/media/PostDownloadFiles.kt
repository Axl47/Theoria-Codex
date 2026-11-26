package com.theoriacodex.app.media

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DownloadedPostFile(val uri: Uri, val mime: String?)

suspend fun downloadedPostPreview(context: Context, file: DownloadedPostFile): Any? =
    withContext(Dispatchers.IO) {
        if (file.mime?.startsWith("video/") != true) return@withContext file.uri
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, file.uri)
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
        }
    }

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

fun openDownloadedPostFile(context: Context, file: DownloadedPostFile): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(file.uri, file.mime ?: "application/octet-stream")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}.isSuccess

fun shareDownloadedPostFileIntent(file: DownloadedPostFile): Intent = Intent.createChooser(
    Intent(Intent.ACTION_SEND).apply {
        type = file.mime ?: "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, file.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    },
    null,
)

fun shareDownloadedPostFile(context: Context, file: DownloadedPostFile): Boolean = runCatching {
    context.startActivity(shareDownloadedPostFileIntent(file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

suspend fun openPostDownload(
    item: PostDownloadItem,
    findFile: suspend (PostDownloadItem) -> DownloadedPostFile?,
    openFile: (DownloadedPostFile) -> Boolean,
    openPost: suspend (PostDownloadItem) -> Unit,
) {
    val file = com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation { findFile(item) }.getOrNull()
    if (file == null || !openFile(file)) openPost(item)
}
