@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoCacheContentionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `playback reads upstream while prefetch still owns the cache range`() {
        val cache = SimpleCache(
            temporaryFolder.newFolder(), NoOpCacheEvictor(),
            StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext()),
        )
        val executor = Executors.newSingleThreadExecutor()
        val payload = byteArrayOf(1, 2, 3, 4)
        val locked = requireNotNull(cache.startReadWriteNonBlocking("post", 0L, payload.size.toLong()))
        try {
            val factory = videoCacheDataSourceFactory(cache, DataSource.Factory { ByteArrayDataSource(payload) })
            val result = executor.submit<ByteArray> {
                val source = factory.createDataSource()
                try {
                    source.open(DataSpec.Builder().setUri("https://example.test/video.mp4").setKey("post").build())
                    ByteArray(payload.size).also { source.read(it, 0, it.size) }
                } finally {
                    source.close()
                }
            }
            // Keep the range locked until playback completes: it must never wait for prefetch.
            assertArrayEquals(payload, result.get(2, TimeUnit.SECONDS))
        } finally {
            cache.releaseHoleSpan(locked)
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
            cache.release()
        }
    }
}
