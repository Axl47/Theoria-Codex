@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoFrameSamplingTest {
    private val uri = Uri.parse("https://cdn.test/video.mp4")

    @Test
    fun `sample points spread from 5 to 95 percent, or the first frame when the length is unknown`() {
        assertEquals(listOf(500_000L, 3_500_000L, 6_500_000L, 9_500_000L), samplePointsUs(durationMs = 10_000L, count = 4))
        assertEquals(listOf(0L), samplePointsUs(durationMs = null, count = 6))
    }

    @Test
    fun `one budget counts every response body it sees, then refuses`() {
        val budget = NetworkBudget(maxBytes = 60)
        val client = OkHttpClient.Builder()
            .addInterceptor(budget.interceptor)
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ByteArray(40).toResponseBody()).build()
            }
            .build()
        fun fetch() = client.newCall(Request.Builder().url("https://cdn.test/video.mp4").build()).execute().body.source()

        assertEquals(40, fetch().readByteArray().size)
        val second = fetch()
        assertEquals(20, second.read(okio.Buffer(), 40))
        assertThrows(NetworkBudgetSpent::class.java) { second.read(okio.Buffer(), 40) }
        assertThrows(NetworkBudgetSpent::class.java) { fetch() }
    }

    @Test
    fun `frames wait for a later try after a network failure, and a refusal asks for a fresh address`() {
        val frames = listOf(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        val spent = HttpDataSource.HttpDataSourceException(NetworkBudgetSpent(), DataSpec(uri), 0, HttpDataSource.HttpDataSourceException.TYPE_READ)

        assertTrue(frameSample(frames, failure = null) is FrameSample.Frames)
        assertEquals(FrameSample.Unreadable, frameSample(emptyList(), failure = null))
        // A spent budget keeps the frames it bought.
        assertTrue(frameSample(frames, spent) is FrameSample.Frames)
        assertEquals(FrameSample.Unreadable, frameSample(emptyList(), spent))
        assertEquals(FrameSample.Refused, frameSample(emptyList(), status(404)))
        assertEquals(FrameSample.Unreachable, frameSample(emptyList(), status(429)))
        assertEquals(FrameSample.Unreachable, frameSample(emptyList(), status(503)))
        assertEquals(FrameSample.Unreachable, frameSample(frames, IOException("connection reset")))
    }

    @Test
    fun `the reader keeps the windows it fetched, so reading them again costs no request`() {
        val source = RecordingSource(ByteArray(1024 * 1024) { it.toByte() })
        val job = Job()
        val reader = DataSourceMediaReader(source, uri, key = null, job)
        val buffer = ByteArray(16)

        assertEquals(1024L * 1024, reader.size)
        assertEquals(16, reader.readAt(10, buffer, 0, 16))
        assertEquals(10.toByte(), buffer[0])
        assertEquals(16, reader.readAt(400_000, buffer, 0, 16))
        assertEquals(400_000.toByte(), buffer[0])
        reader.readAt(20, buffer, 0, 16)
        reader.readAt(400_100, buffer, 0, 16)

        // The first request asks for the whole file to learn its length; a jump asks for one bounded window.
        assertEquals(listOf(0L to C.LENGTH_UNSET.toLong(), 400_000L to 256L * 1024), source.opened)

        // Once its job ends, the reader gives the retriever nothing more, kept windows included.
        job.cancel()
        assertEquals(-1, reader.readAt(10, buffer, 0, 16))
    }

    private fun status(code: Int) =
        HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), DataSpec(uri), ByteArray(0))

    /** Serves [bytes] and records each request's position and length. */
    private class RecordingSource(bytes: ByteArray) : DataSource {
        private val inner = ByteArrayDataSource(bytes)
        val opened = mutableListOf<Pair<Long, Long>>()

        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec.position to dataSpec.length
            return inner.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit

        override fun getUri(): Uri? = inner.uri

        override fun close() = inner.close()
    }
}
