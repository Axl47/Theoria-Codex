@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BoundedVideoCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var cache: SimpleCache

    @Before fun createCache() {
        cache = SimpleCache(
            temporaryFolder.newFolder(), NoOpCacheEvictor(),
            StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext()),
        )
    }

    @After fun releaseCache() = cache.release()

    @Test fun `a reader ahead of a playing request still caches what it reads`() {
        val origin = FakeOrigin(Random(1).nextBytes(10 * CHUNK.toInt()))
        val factory = videoCacheDataSourceFactory(cache, origin, chunkBytes = CHUNK)
        val player = factory.createDataSource()
        player.open(spec(0L))
        player.read(ByteArray(10), 0, 10)
        try {
            // A scrub preview past the playing position: before bounding, the player's lock reached the end of the file.
            factory.createDataSource().readAndClose(spec(5 * CHUNK), 500)
            assertEquals(500L, cache.getCachedBytes(KEY, 5 * CHUNK, 500L))
        } finally {
            player.close()
        }
    }

    @Test fun `chunked reads cache the whole file and its length, never asking past the end`() {
        listOf(5 * CHUNK, 5 * CHUNK + 1, CHUNK - 1, 1L).forEach { size ->
            val content = Random(size).nextBytes(size.toInt())
            val origin = FakeOrigin(content)
            val key = "video-$size"
            val read = videoCacheDataSourceFactory(cache, origin, chunkBytes = CHUNK).createDataSource()
                .readAndClose(spec(0L, key = key), Int.MAX_VALUE)

            assertArrayEquals(content, read)
            assertEquals(size, ContentMetadata.getContentLength(cache.getContentMetadata(key)))
            assertTrue(origin.requests.all { (position, _) -> position < size })
            // An offline save copies a fully watched video from the cache.
            val saved = temporaryFolder.newFile()
            assertTrue(runBlocking { copyFullyCachedVideo(cache, key, saved) })
            assertArrayEquals(content, saved.readBytes())
        }
    }

    @Test fun `a server that ignores ranges is read unbounded after its first response`() {
        val content = Random(3).nextBytes(5 * CHUNK.toInt())
        val origin = FakeOrigin(content, honorsRanges = false)
        val read = videoCacheDataSourceFactory(cache, origin, chunkBytes = CHUNK).createDataSource()
            .readAndClose(spec(0L), Int.MAX_VALUE)

        assertArrayEquals(content, read)
        assertEquals(listOf(0L to CHUNK, CHUNK to UNSET), origin.requests)
        assertEquals(content.size + CHUNK, origin.transferred)
    }

    @Test fun `a prefetch longer than the file stops at its end`() {
        val content = Random(4).nextBytes((3 * CHUNK + 7).toInt())
        val origin = FakeOrigin(content)
        val source = videoCacheDataSourceFactory(cache, origin, blockOnCache = true, chunkBytes = CHUNK).createDataSource()
        CacheWriter(source, spec(0L).buildUpon().setLength(20 * CHUNK).build(), null, null).cache()

        assertTrue(cache.isCached(KEY, 0L, content.size.toLong()))
        assertEquals(content.size.toLong(), ContentMetadata.getContentLength(cache.getContentMetadata(KEY)))
        assertTrue(origin.requests.all { (position, _) -> position < content.size })
    }

    private fun spec(position: Long, key: String = KEY): DataSpec =
        DataSpec.Builder().setUri("https://example.test/$key.mp4").setKey(key).setPosition(position).build()

    private fun DataSource.readAndClose(dataSpec: DataSpec, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        try {
            open(dataSpec)
            val buffer = ByteArray(4096)
            while (output.size() < maxBytes) {
                val read = read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                if (read == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, read)
            }
        } finally {
            close()
        }
        return output.toByteArray()
    }

    private companion object {
        const val CHUNK = 1_000L
        const val KEY = "video"
    }
}

private const val UNSET = C.LENGTH_UNSET.toLong()

/**
 * Serves [content] as `DefaultHttpDataSource` reads an HTTP origin: a ranged request gets the clamped range with a
 * `Content-Range`, or, when [honorsRanges] is false, the whole file, skipped to the position like a 200 response.
 */
private class FakeOrigin(private val content: ByteArray, private val honorsRanges: Boolean = true) : DataSource.Factory {
    val requests = mutableListOf<Pair<Long, Long>>()
    var transferred = 0L
        private set

    override fun createDataSource(): DataSource = object : DataSource {
        private var position = 0L
        private var remaining = 0L
        private var uri: Uri? = null
        private var headers = emptyMap<String, List<String>>()

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            requests += dataSpec.position to dataSpec.length
            check(dataSpec.position < content.size) { "416: request at ${dataSpec.position} of ${content.size}" }
            val end = if (dataSpec.length == UNSET) content.size.toLong()
            else minOf(content.size.toLong(), dataSpec.position + dataSpec.length)
            val ranged = dataSpec.position != 0L || dataSpec.length != UNSET
            headers = if (honorsRanges && ranged) {
                mapOf("content-range" to listOf("bytes ${dataSpec.position}-${end - 1}/${content.size}"))
            } else {
                if (!honorsRanges) transferred += dataSpec.position
                emptyMap()
            }
            uri = dataSpec.uri
            position = dataSpec.position
            remaining = end - position
            return if (dataSpec.length != UNSET) dataSpec.length else remaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val count = minOf(length.toLong(), remaining).toInt()
            System.arraycopy(content, position.toInt(), buffer, offset, count)
            position += count
            remaining -= count
            transferred += count
            return count
        }

        override fun getUri(): Uri? = uri

        override fun getResponseHeaders(): Map<String, List<String>> = headers

        override fun close() {
            uri = null
        }
    }
}
