package com.theoriacodex.app.media

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil.disk.DiskCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import java.io.IOException
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UndecodableImageEvictionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a cached body that fails to decode is dropped while network failures keep the cache`() {
        val cache = DiskCache.Builder().directory(folder.root.toOkioPath()).maxSizeBytes(1_000_000L).build()
        val eviction = UndecodableImageEviction(cache)
        val undecodable = "https://media.example/page.jpg"
        val unreachable = "https://media.example/other.jpg"
        listOf(undecodable, unreachable).forEach { key -> cache.openEditor(key)!!.commitAndOpenSnapshot()!!.close() }

        eviction.onError(request(unreachable), ErrorResult(null, request(unreachable), IOException("offline")))
        eviction.onError(request(undecodable), ErrorResult(null, request(undecodable), IllegalStateException("not an image")))

        assertNull(cache.openSnapshot(undecodable))
        assertNotNull(cache.openSnapshot(unreachable)?.also { it.close() })
    }

    private fun request(url: String) = ImageRequest.Builder(ApplicationProvider.getApplicationContext()).data(url).build()
}
