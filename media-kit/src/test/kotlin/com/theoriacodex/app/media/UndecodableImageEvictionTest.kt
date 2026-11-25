package com.theoriacodex.app.media

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil.disk.DiskCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.network.HttpException
import java.io.IOException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
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
    fun `a cached body that fails to decode is dropped while network and HTTP failures keep the cache (ADR 0108)`() {
        val cache = DiskCache.Builder().directory(folder.root.toOkioPath()).maxSizeBytes(1_000_000L).build()
        val eviction = UndecodableImageEviction(cache)
        val undecodable = "https://media.example/page.jpg"
        val unreachable = "https://media.example/other.jpg"
        val refused = "https://media.example/refused.jpg"
        listOf(undecodable, unreachable, refused).forEach { key -> cache.openEditor(key)!!.commitAndOpenSnapshot()!!.close() }
        val forbidden = Response.Builder().request(Request.Builder().url(refused).build()).protocol(Protocol.HTTP_1_1)
            .code(403).message("Forbidden").build()

        eviction.onError(request(unreachable), ErrorResult(null, request(unreachable), IOException("offline")))
        eviction.onError(request(refused), ErrorResult(null, request(refused), HttpException(forbidden)))
        eviction.onError(request(undecodable), ErrorResult(null, request(undecodable), IllegalStateException("not an image")))

        assertNull(cache.openSnapshot(undecodable))
        assertNotNull(cache.openSnapshot(unreachable)?.also { it.close() })
        assertNotNull(cache.openSnapshot(refused)?.also { it.close() })
    }

    private fun request(url: String) = ImageRequest.Builder(ApplicationProvider.getApplicationContext()).data(url).build()
}
