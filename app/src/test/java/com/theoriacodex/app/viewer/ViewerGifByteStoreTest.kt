package com.theoriacodex.app.viewer

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ViewerGifByteStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `prefetch and playback share one acquisition and survive reconstruction`() = runBlocking {
        val directory = temporary.newFolder()
        val store = ViewerGifByteStore(directory)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val bytes = "GIF89a fixture".toByteArray()
        val prefetch = async { store.load("PIXIV:media") {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
            bytes
        } }
        started.await()
        val playback = async { store.load("PIXIV:media") { calls.incrementAndGet(); bytes } }
        release.complete(Unit)
        assertArrayEquals(bytes, prefetch.await())
        assertArrayEquals(bytes, playback.await())
        assertEquals(1, calls.get())
        val reopened = ViewerGifByteStore(directory)
        assertArrayEquals(bytes, reopened.load("PIXIV:media") { error("must reuse cached bytes") })
        assertArrayEquals(bytes, File(requireNotNull(reopened.cachedLocation("PIXIV:media"))).readBytes())
    }

    @Test
    fun `cancelled prefetch frees acquisition for foreground playback`() = runBlocking {
        val store = ViewerGifByteStore(temporary.newFolder())
        val started = CompletableDeferred<Unit>()
        val prefetch = launch { store.load("cancel") { started.complete(Unit); awaitCancellation() } }
        started.await()
        prefetch.cancelAndJoin()
        val expected = byteArrayOf(1, 2, 3)
        assertArrayEquals(expected, store.load("cancel") { expected })
    }

    @Test
    fun `source identities remain isolated and expired entries refresh`() = runBlocking {
        val directory = temporary.newFolder()
        val store = ViewerGifByteStore(directory)
        store.load("PIXIV:same-url") { byteArrayOf(1) }
        store.load("GELBOORU:same-url") { byteArrayOf(2) }
        assertArrayEquals(byteArrayOf(2), store.load("GELBOORU:same-url") { error("cache miss") })
        File(requireNotNull(store.cachedLocation("PIXIV:same-url"))).setLastModified(1L)
        assertArrayEquals(byteArrayOf(3), store.load("PIXIV:same-url") { byteArrayOf(3) })
    }

    @Test
    fun `disk entry budget retains the latest file`() = runBlocking {
        val directory = temporary.newFolder()
        val store = ViewerGifByteStore(directory)
        repeat(70) { index -> store.load("key-$index") { byteArrayOf(1) } }
        assertEquals(64, directory.listFiles().orEmpty().size)
        assertNotNull(store.cachedLocation("key-69"))
    }
}
