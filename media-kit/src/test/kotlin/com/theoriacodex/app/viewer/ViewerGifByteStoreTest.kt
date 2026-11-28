package com.theoriacodex.app.viewer

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            it.writeBytes(bytes)
        } }
        started.await()
        val playback = async { store.load("PIXIV:media") { calls.incrementAndGet(); it.writeBytes(bytes) } }
        release.complete(Unit)
        assertArrayEquals(bytes, prefetch.await())
        assertArrayEquals(bytes, playback.await())
        assertEquals(1, calls.get())
        val reopened = ViewerGifByteStore(directory)
        assertArrayEquals(bytes, reopened.load("PIXIV:media") { error("must reuse cached bytes") })
        assertArrayEquals(bytes, File(requireNotNull(reopened.cachedLocation("PIXIV:media"))).readBytes())
    }

    @Test
    fun `every GIF fills under its own lock, so seventeen can download at once (ADR 0226)`() = runBlocking {
        val store = ViewerGifByteStore(temporary.newFolder())
        val keys = (1..17).map { "GELBOORU:gif-$it" }
        val started = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        withTimeout(5_000L) {
            keys.map { key ->
                async(Dispatchers.IO) {
                    store.warm(key) { file ->
                        if (started.incrementAndGet() == keys.size) allStarted.complete(Unit)
                        allStarted.await()
                        file.writeBytes("GIF89a $key".toByteArray())
                    }
                }
            }.awaitAll()
        }
        assertEquals(keys.size, started.get())
    }

    @Test
    fun `a fill is staged in a temporary file and a clear during it publishes nothing (ADR 0108)`() = runBlocking {
        val directory = temporary.newFolder()
        val store = ViewerGifByteStore(directory)
        val filled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bytes = "GIF89a staged".toByteArray()
        val warm = async { store.warm("PIXIV:staged") { file -> file.writeBytes(bytes); filled.complete(Unit); release.await() } }
        filled.await()
        assertEquals(listOf(true), directory.listFiles().orEmpty().map { it.name.endsWith(".tmp") })
        assertNull(store.cachedLocation("PIXIV:staged"))

        store.clear()
        release.complete(Unit)

        assertEquals(bytes.size.toLong(), warm.await())
        assertNull(store.cachedLocation("PIXIV:staged"))
        assertEquals(emptyList<String>(), directory.list().orEmpty().toList())
    }

    @Test
    fun `warming reports the cached size and playback reuses the entry`() = runBlocking {
        val store = ViewerGifByteStore(temporary.newFolder())
        val bytes = "GIF89a warmed".toByteArray()
        assertEquals(bytes.size.toLong(), store.warm("PIXIV:warm") { it.writeBytes(bytes) })
        assertEquals(bytes.size.toLong(), store.warm("PIXIV:warm") { error("must reuse cached bytes") })
        assertArrayEquals(bytes, store.load("PIXIV:warm") { error("must reuse cached bytes") })
    }

    @Test
    fun `cancelled prefetch frees acquisition for foreground playback`() = runBlocking {
        val store = ViewerGifByteStore(temporary.newFolder())
        val started = CompletableDeferred<Unit>()
        val prefetch = launch { store.load("cancel") { started.complete(Unit); awaitCancellation() } }
        started.await()
        prefetch.cancelAndJoin()
        val expected = byteArrayOf(1, 2, 3)
        assertArrayEquals(expected, store.load("cancel") { it.writeBytes(expected) })
    }

    @Test
    fun `source identities remain isolated and expired entries refresh`() = runBlocking {
        val directory = temporary.newFolder()
        val store = ViewerGifByteStore(directory)
        store.load("PIXIV:same-url") { it.writeBytes(byteArrayOf(1)) }
        store.load("GELBOORU:same-url") { it.writeBytes(byteArrayOf(2)) }
        assertArrayEquals(byteArrayOf(2), store.load("GELBOORU:same-url") { error("cache miss") })
        File(requireNotNull(store.cachedLocation("PIXIV:same-url"))).setLastModified(1L)
        assertArrayEquals(byteArrayOf(3), store.load("PIXIV:same-url") { it.writeBytes(byteArrayOf(3)) })
    }

    @Test
    fun `neighbour warms never trim the current page's GIF, even one loaded before its page became current (ADR 0226)`() = runBlocking {
        val store = ViewerGifByteStore(temporary.newFolder(), maxBytes = 3_000)
        suspend fun age(key: String, minutes: Long) =
            File(requireNotNull(store.cachedLocation(key))).setLastModified(System.currentTimeMillis() - minutes * 60_000)
        store.load("PIXIV:current") { it.writeBytes(ByteArray(1_000)) }
        store.markCurrent(listOf("PIXIV:current"))
        age("PIXIV:current", 10)
        repeat(4) { index -> store.warm("PIXIV:neighbour-$index") { it.writeBytes(ByteArray(1_000)) } }
        assertNotNull(store.cachedLocation("PIXIV:current"))

        store.markCurrent(listOf("PIXIV:next"))
        store.load("PIXIV:next") { it.writeBytes(ByteArray(1_000)) }
        repeat(4) { index -> store.warm("PIXIV:later-$index") { it.writeBytes(ByteArray(1_000)) } }
        assertNull(store.cachedLocation("PIXIV:current"))
        assertNotNull(store.cachedLocation("PIXIV:next"))
    }

    @Test
    fun `disk entry budget retains the latest file`() = runBlocking {
        val directory = temporary.newFolder()
        val store = ViewerGifByteStore(directory)
        repeat(70) { index -> store.load("key-$index") { it.writeBytes(byteArrayOf(1)) } }
        assertEquals(64, directory.listFiles().orEmpty().size)
        assertNotNull(store.cachedLocation("key-69"))
    }
}
