package com.theoriacodex.sources.hitomi

import com.google.gson.JsonObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HitomiGalleryManifestStoreTest {
    @Test
    fun `concurrent consumers single flight one gallery load`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val gallery = JsonObject().apply { addProperty("id", "42") }
        val store = store(scope = this)

        val consumers = List(8) {
            async {
                store.getOrLoad(42) {
                    calls.incrementAndGet()
                    gate.await()
                    HitomiGalleryManifest(gallery, payloadBytes = 256L)
                }
            }
        }
        runCurrent()
        gate.complete(Unit)
        val results = consumers.awaitAll()

        assertEquals(1, calls.get())
        results.forEach { result -> assertSame(gallery, result) }
    }

    @Test
    fun `successful manifest is reused until ttl then replaced`() = runTest {
        var now = 0L
        var calls = 0
        val store = store(nowMillis = { now }, scope = this)
        suspend fun load(): JsonObject? = store.getOrLoad(7) {
            calls += 1
            HitomiGalleryManifest(
                JsonObject().apply { addProperty("generation", calls) },
                payloadBytes = 128L,
            )
        }

        assertEquals(1, load()?.get("generation")?.asInt)
        now = 119_999L
        assertEquals(1, load()?.get("generation")?.asInt)
        now = 120_001L
        assertEquals(2, load()?.get("generation")?.asInt)
        assertEquals(2, calls)
    }

    @Test
    fun `missing and failed manifests never poison the cache`() = runTest {
        var missingCalls = 0
        val store = store(scope = this)

        repeat(2) {
            assertEquals(null, store.getOrLoad(1) { missingCalls += 1; null })
        }
        val failure = runCatching {
            store.getOrLoad(2) { throw IOException("temporary") }
        }.exceptionOrNull()
        val recovered = store.getOrLoad(2) {
            HitomiGalleryManifest(JsonObject().apply { addProperty("id", 2) }, 64L)
        }

        assertEquals(2, missingCalls)
        assertTrue(failure is IOException)
        assertEquals(2, recovered?.get("id")?.asInt)
    }

    @Test
    fun `byte budget rejects a manifest larger than the entire cache`() = runTest {
        var calls = 0
        val store = HitomiGalleryManifestStore(
            maxBytes = 256L,
            ttlMillis = 120_000L,
            nowMillis = { 0L },
            scope = this,
        )

        repeat(2) {
            store.getOrLoad(9) {
                calls += 1
                HitomiGalleryManifest(JsonObject(), payloadBytes = 512L)
            }
        }

        assertEquals(2, calls)
        assertEquals(0L, store.snapshot().cachedBytes)
    }

    private fun store(
        nowMillis: () -> Long = { 0L },
        scope: kotlinx.coroutines.CoroutineScope,
    ) = HitomiGalleryManifestStore(
        maxBytes = 4L * 1024L,
        ttlMillis = 120_000L,
        nowMillis = nowMillis,
        scope = scope,
    )
}
