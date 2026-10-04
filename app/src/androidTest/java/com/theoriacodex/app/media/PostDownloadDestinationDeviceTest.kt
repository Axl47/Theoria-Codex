package com.theoriacodex.app.media

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PostDownloadDestinationDeviceTest {
    @Test
    fun unicodeGalleryDestinationsRemainDistinctAndRepeatPreservesOriginal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.theoriacodex.debug")
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val folder = File(requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)), "destination-${UUID.randomUUID()}")
        assertTrue(folder.mkdirs())
        val ids = mutableListOf<Long>()
        val bytes = "device gallery bytes".toByteArray()
        try {
            LoopbackDownload(bytes).use { server ->
                val files = listOf("猫" to "1", "犬" to "2").map { (title, id) ->
                    val media = ImageRef(server.url, null, "image/png")
                    val post = Post(
                        id = PostId(SourceKey.PIXIV, id), preview = media, full = media,
                        pageUrl = null, width = null, height = null, canonicalTags = emptyList(),
                        rawTags = emptyList(), authorName = null, createdAtEpochMs = null, title = title,
                    )
                    val name = PostDownloadService.buildDownloadFileName(post, media, server.url, 1, 3)
                    assertTrue(name.startsWith(title))
                    assertTrue(name.endsWith("_p2.png"))
                    val file = File(folder, name)
                    val downloadId = enqueue(manager, server.url, file).also(ids::add)
                    assertEquals(PostDownloadStatus.SAVED, awaitTerminal(manager, downloadId))
                    assertArrayEquals(bytes, file.readBytes())
                    file
                }
                assertNotEquals(files[0].name, files[1].name)
                val replacement = "new bytes must not replace the original".toByteArray()
                server.responseBytes = replacement
                val repeat = enqueue(manager, server.url, files.first()).also(ids::add)
                val repeatStatus = awaitTerminal(manager, repeat)
                assertTrue(repeatStatus in setOf(PostDownloadStatus.ALREADY_EXISTS, PostDownloadStatus.SAVED))
                assertArrayEquals(bytes, files.first().readBytes())
                if (repeatStatus == PostDownloadStatus.SAVED) {
                    manager.query(DownloadManager.Query().setFilterById(repeat)).use { cursor ->
                        check(cursor.moveToFirst())
                        val uri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
                        val numberedCopy = File(requireNotNull(uri.path))
                        assertEquals(folder.canonicalPath, numberedCopy.parentFile?.canonicalPath)
                        assertNotEquals(files.first().canonicalPath, numberedCopy.canonicalPath)
                        assertArrayEquals(replacement, numberedCopy.readBytes())
                    }
                }
            }
        } finally {
            // All rows and files are created by this isolated test, outside the user's Downloads.
            if (ids.isNotEmpty()) manager.remove(*ids.toLongArray())
            folder.deleteRecursively()
        }
    }

    private fun enqueue(manager: DownloadManager, url: String, destination: File): Long = manager.enqueue(
        DownloadManager.Request(Uri.parse(url)).setDestinationUri(Uri.fromFile(destination)),
    )

    private fun awaitTerminal(manager: DownloadManager, id: Long): PostDownloadStatus {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                check(cursor.moveToFirst())
                val status = downloadTransferSnapshot(
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)), 0, -1,
                ).status
                if (status in setOf(PostDownloadStatus.SAVED, PostDownloadStatus.ALREADY_EXISTS, PostDownloadStatus.FAILED)) return status
            }
            SystemClock.sleep(100L)
        }
        error("Android download $id did not settle")
    }
}

private class LoopbackDownload(bytes: ByteArray) : AutoCloseable {
    @Volatile var responseBytes: ByteArray = bytes
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}/page.png"
    private val worker = thread(name = "download-destination-fixture", isDaemon = true) {
        try {
            while (!server.isClosed) server.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream().bufferedReader()
                var headerCount = 0
                while (!input.readLine().isNullOrEmpty()) check(++headerCount <= 100)
                val response = responseBytes
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(response)
                    flush()
                }
            }
        } catch (error: SocketException) {
            if (!server.isClosed) throw error
        }
    }

    override fun close() {
        server.close()
        worker.join(6_000L)
        check(!worker.isAlive)
    }
}
