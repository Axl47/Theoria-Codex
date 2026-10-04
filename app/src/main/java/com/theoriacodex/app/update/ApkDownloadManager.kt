package com.theoriacodex.app.update

import android.content.Context
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.sources.http.executeCancellableHttpConnection
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ApkDownloadManager(
    private val context: Context,
    private val outputFileName: String,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val maximumBytes: Long = 512L * 1024 * 1024,
    private val timeoutMs: Long = 300_000L,
) {
    private val mutex = Mutex()
    private var cleanedStaging = false

    fun outputFile(): File = File(context.filesDir, "theoria_codex/updates/$outputFileName")

    suspend fun download(remote: RemoteUpdate, onProgress: (Float?) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatchingPreservingCancellation {
            mutex.withLock {
                withTimeoutOrNull(timeoutMs) { downloadInside(remote, onProgress) }
                    ?: throw IOException("Update download timed out")
            }
        }
    }

    private suspend fun downloadInside(remote: RemoteUpdate, onProgress: (Float?) -> Unit): File {
        val finalFile = outputFile()
        val updatesDir = requireNotNull(finalFile.parentFile)
        check(updatesDir.isDirectory || updatesDir.mkdirs()) { "Could not create update directory" }
        if (!cleanedStaging) {
            updatesDir.listFiles().orEmpty().filter { it.name == "$outputFileName.part" ||
                it.name.startsWith("update-") && it.name.endsWith(".part") }.forEach(File::delete)
            cleanedStaging = true
        }
        val part = File.createTempFile("update-", ".part", updatesDir)
        val requestContext = currentCoroutineContext()
        try {
            val connection = connectionFactory(URL(remote.assetDownloadUrl))
            executeCancellableHttpConnection(connection) {
                try { acquire(connection, remote, part, requestContext, onProgress) }
                finally { if (!requestContext.isActive) part.delete() }
            }
            requestContext.ensureActive()
            // Only the still-admitted caller may publish; late blocking reads write their unique staging file.
            Files.move(part.toPath(), finalFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            return finalFile
        } finally { part.delete() }
    }

    private fun acquire(
        connection: HttpURLConnection,
        remote: RemoteUpdate,
        part: File,
        requestContext: CoroutineContext,
        onProgress: (Float?) -> Unit,
    ) {
        requestContext.ensureActive()
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "TheoriaCodexUpdater")
        connection.instanceFollowRedirects = true
        val status = connection.responseCode
        if (status != HttpURLConnection.HTTP_OK) throw IOException("Update download failed ($status)")
        val declared = connection.contentLengthLong.takeIf { it > 0 }
        val expected = remote.assetSizeBytes?.takeIf { it > 0 } ?: declared
        if ((expected ?: 0) > maximumBytes || (declared ?: 0) > maximumBytes) throw IOException("Update APK exceeds the size limit")
        if (expected != null && declared != null && expected != declared) throw IOException("Update APK size does not match release metadata")
        val limit = expected ?: maximumBytes
        requestContext.ensureActive()
        connection.inputStream.use { input ->
            FileOutputStream(part).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                var lastReported = -2
                while (true) {
                    requestContext.ensureActive()
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - downloaded + 1).toInt())
                    if (read < 0) break
                    requestContext.ensureActive()
                    downloaded += read
                    if (downloaded > limit) throw IOException("Update APK exceeds its expected size")
                    output.write(buffer, 0, read)
                    val progress = expected?.let { downloaded.toFloat() / it }
                    val bucket = progress?.let { (it * 100).toInt() } ?: -1
                    if (bucket != lastReported) { onProgress(progress); lastReported = bucket }
                }
                if (downloaded == 0L || expected != null && downloaded != expected) throw IOException("Update APK download is incomplete")
                requestContext.ensureActive()
                output.fd.sync()
            }
        }
    }
}
