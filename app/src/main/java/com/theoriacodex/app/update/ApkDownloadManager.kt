package com.theoriacodex.app.update

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApkDownloadManager(
    private val context: Context,
    private val outputFileName: String,
) {
    fun outputFile(): File {
        return File(context.filesDir, "theoria_codex/updates/$outputFileName")
    }

    suspend fun download(
        remote: RemoteUpdate,
        onProgress: (Float?) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.filesDir, "theoria_codex/updates")
            if (!updatesDir.exists()) {
                updatesDir.mkdirs()
            }
            val partFile = File(updatesDir, "$outputFileName.part")
            val finalFile = outputFile()

            val connection = (URL(remote.assetDownloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "TheoriaCodexUpdater")
                instanceFollowRedirects = true
            }

            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IOException("Update download failed ($status)")
                }

                val totalBytes = remote.assetSizeBytes ?: connection.contentLengthLong.takeIf { it > 0L }
                connection.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var lastReported = -1

                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val progress = totalBytes?.takeIf { it > 0L }?.let { total ->
                                (downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                            }
                            val progressBucket = progress?.let { (it * 100f).toInt() } ?: -1
                            if (progressBucket != lastReported) {
                                onProgress(progress)
                                lastReported = progressBucket
                            }
                        }
                        output.flush()
                    }
                }
            } finally {
                connection.disconnect()
            }

            if (finalFile.exists()) {
                finalFile.delete()
            }
            if (!partFile.renameTo(finalFile)) {
                throw IOException("Could not finalize downloaded update APK")
            }
            finalFile
        }
    }
}
