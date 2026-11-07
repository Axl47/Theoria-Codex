@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoCacheCopyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val payload = ByteArray(10_000) { it.toByte() }
    private val cache by lazy {
        SimpleCache(
            temporaryFolder.newFolder(), NoOpCacheEvictor(),
            StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext()),
        )
    }

    @After fun release() = cache.release()

    @Test fun `a video cached in full is copied byte for byte`() = runBlocking {
        cacheVideo(length = null)
        val destination = temporaryFolder.newFile()
        assertTrue(copyFullyCachedVideo(cache, KEY, destination))
        assertArrayEquals(payload, destination.readBytes())
    }

    @Test fun `a video cached only from its start is left to the download`() = runBlocking {
        cacheVideo(length = 4_000L)
        assertFalse(copyFullyCachedVideo(cache, KEY, temporaryFolder.newFile()))
    }

    @Test fun `an uncached video is left to the download`() = runBlocking {
        assertFalse(copyFullyCachedVideo(cache, KEY, temporaryFolder.newFile()))
    }

    private fun cacheVideo(length: Long?) {
        val upstream = videoCacheDataSourceFactory(cache, DataSource.Factory { ByteArrayDataSource(payload) })
        val spec = DataSpec.Builder().setUri("https://example.test/video.mp4").setKey(KEY)
            .apply { if (length != null) setLength(length) }.build()
        CacheWriter(upstream.createDataSource(), spec, null, null).cache()
    }

    private companion object {
        const val KEY = "theoria-video-v1-test"
    }
}
