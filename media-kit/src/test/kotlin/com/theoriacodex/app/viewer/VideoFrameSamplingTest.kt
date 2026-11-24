@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
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
    fun `sample points spread from 5 to 95 percent, or the first frame when the length is unknown (ADR 0107)`() {
        assertEquals(listOf(500_000L, 3_500_000L, 6_500_000L, 9_500_000L), samplePointsUs(durationMs = 10_000L, count = 4))
        assertEquals(listOf(0L), samplePointsUs(durationMs = null, count = 6))
    }

    @Test
    fun `one budget counts every response body it sees, then refuses (ADR 0107)`() {
        val budget = NetworkBudget(maxBytes = 60)
        var calls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(budget.interceptor)
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (calls++ == 0) 200 else 404).message("OK")
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
    fun `frames wait for a later try after a network failure or a sign-in, and a refusal asks for a fresh address (ADR 0107)`() {
        val frames = listOf(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        val spent = HttpDataSource.HttpDataSourceException(NetworkBudgetSpent(), DataSpec(uri), 0, HttpDataSource.HttpDataSourceException.TYPE_READ)

        assertTrue(frameSample(frames, failure = null) is FrameSample.Frames)
        assertEquals(FrameSample.Unreadable, frameSample(emptyList(), failure = null))
        assertTrue(frameSample(frames, spent) is FrameSample.Frames)
        assertEquals(FrameSample.Unreadable, frameSample(emptyList(), spent))
        assertEquals(FrameSample.Refused, frameSample(emptyList(), status(404)))
        assertEquals(FrameSample.Unreachable, frameSample(emptyList(), status(429)))
        assertEquals(FrameSample.Unreachable, frameSample(emptyList(), status(503)))
        assertEquals(FrameSample.Unreachable, frameSample(frames, IOException("connection reset")))
        assertEquals(FrameSample.Refused, frameSample(emptyList(), MediaRefused("gone")))
        assertEquals(FrameSample.Unreadable, frameSample(emptyList(), UnreadableMedia("damaged")))
        val signedOut = SourceAdapterException(SourceFailureReason.AUTH_REQUIRED, "Pixiv credentials not configured")
        assertEquals(FrameSample.Unreachable, frameSample(emptyList(), signedOut))
        assertEquals(FrameSample.Unreadable, frameSample(emptyList(), SourceAdapterException(SourceFailureReason.PARSE, "unreadable listing")))
        assertEquals(FrameSample.Unreadable, frameSample(emptyList(), SourceAdapterException(SourceFailureReason.UNKNOWN, "unexpected answer")))
        assertEquals(FrameSample.Unreachable, frameSample(emptyList(), status(408)))
        listOf(SourceFailureReason.AUTH_EXPIRED, SourceFailureReason.RATE_LIMITED, SourceFailureReason.NETWORK).forEach { reason ->
            assertEquals(reason.name, FrameSample.Unreachable, frameSample(frames, SourceAdapterException(reason, "try later")))
        }
        assertTrue(frameSample(frames, SourceAdapterException(SourceFailureReason.PARSE, "unreadable listing")) is FrameSample.Frames)
        assertTrue(frameSample(frames, UnreadableMedia("damaged")) is FrameSample.Frames)
        assertEquals(FrameSample.Unreachable, frameSample(frames, status(503)))
        assertEquals(FrameSample.Refused, frameSample(frames, status(404)))
    }

    @Test
    fun `the reader keeps the windows it fetched, so reading them again costs no request (ADR 0107)`() {
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

        assertEquals(listOf(0L to C.LENGTH_UNSET.toLong(), 400_000L to 256L * 1024), source.opened)

        job.cancel()
        assertEquals(-1, reader.readAt(10, buffer, 0, 16))
    }

    @Test
    fun `the reader keeps its last four windows and refetches an evicted one (ADR 0107)`() {
        val window = 256L * 1024
        val source = RecordingSource(ByteArray(2 * 1024 * 1024) { it.toByte() })
        val reader = DataSourceMediaReader(source, uri, key = null, Job())
        val buffer = ByteArray(16)

        (1..4).forEach { assertEquals(16, reader.readAt(it * window, buffer, 0, 16)) }
        assertEquals(16, reader.readAt(4 * window + 100, buffer, 0, 16))
        assertEquals(16, reader.readAt(10, buffer, 0, 16))
        assertEquals(10.toByte(), buffer[0])

        assertEquals(
            listOf(0L to C.LENGTH_UNSET.toLong()) + (1..4).map { it * window to window } + listOf(10L to window),
            source.opened,
        )
    }

    @Test
    fun `after the reader's first failure a read outside its kept windows fails while a kept window is still served (ADR 0107)`() {
        val window = 256L * 1024
        val source = RecordingSource(ByteArray(2 * 1024 * 1024) { it.toByte() }, failsAt = window)
        val reader = DataSourceMediaReader(source, uri, key = null, Job())
        val buffer = ByteArray(16)

        assertEquals(-1, reader.readAt(window, buffer, 0, 16))
        assertEquals(-1, reader.readAt(2 * window, buffer, 0, 16))
        assertEquals(16, reader.readAt(10, buffer, 0, 16))
        assertEquals(10.toByte(), buffer[0])

        assertTrue(reader.failure is IOException)
        assertEquals(listOf(0L to C.LENGTH_UNSET.toLong(), window to window), source.opened)
    }

    private fun status(code: Int) =
        HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), DataSpec(uri), ByteArray(0))

    private class RecordingSource(bytes: ByteArray, private val failsAt: Long? = null) : DataSource {
        private val inner = ByteArrayDataSource(bytes)
        val opened = mutableListOf<Pair<Long, Long>>()

        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec.position to dataSpec.length
            if (dataSpec.position == failsAt) throw IOException("connection reset")
            return inner.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit

        override fun getUri(): Uri? = inner.uri

        override fun close() = inner.close()
    }
}
