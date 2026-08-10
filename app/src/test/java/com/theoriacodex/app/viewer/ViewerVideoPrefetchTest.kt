package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerVideoPrefetchTest {
    @Test
    fun `warmed bytes are explicit and skipped or failed work cannot claim cache data`() {
        assertEquals(
            ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED, bytesCached = 4_096L),
            ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED, bytesCached = 4_096L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED, bytesCached = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ViewerPrefetchResult(ViewerPrefetchOutcome.FAILED, bytesCached = 1L)
        }
    }

    @Test
    fun `video warm target supports media larger than the retired manual limit`() {
        assertEquals(24L * 1024L * 1024L, VIEWER_VIDEO_PREFETCH_BYTES)
        assertTrue(VIEWER_VIDEO_PREFETCH_BYTES > 16L * 1024L * 1024L)
        assertTrue(VIEWER_VIDEO_PREFETCH_BYTES < VIDEO_PLAYBACK_CACHE_MAX_BYTES)
    }

    @Test
    fun `prefetch and playback share CacheWriter identity without a legacy cache owner`() {
        val source = repositoryFile(
            "app/src/main/java/com/theoriacodex/app/viewer/VideoPlaybackInfrastructure.kt",
        ).readText()
        val platform = repositoryFile(
            "app/src/main/java/com/theoriacodex/app/viewer/ViewerPlatformPrefetcher.kt",
        ).readText()

        assertTrue("CacheWriter" in source)
        assertTrue("cacheDataSourceFactory(bound, headers)" in source)
        assertTrue("setKey(cacheKey)" in source)
        assertTrue("videoPlaybackInfrastructure().prefetch(location, headers)" in platform)
        assertFalse("theoria_codex/viewer/videos" in source + platform)
    }

    private fun repositoryFile(path: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(current, "settings.gradle.kts").exists()) {
            current = current.parentFile ?: error("Could not locate repository root")
        }
        return File(current, path)
    }
}
