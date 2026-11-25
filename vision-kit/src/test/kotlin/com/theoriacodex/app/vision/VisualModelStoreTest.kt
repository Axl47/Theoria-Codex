package com.theoriacodex.app.vision

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VisualModelStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deleted = AtomicInteger()

    private val modelBytes = ByteArray(300_000) { (it * 31).toByte() }
    private val vocabularyBytes = "tag_id,name,category,count\n1,solo,0,10\n".toByteArray()
    private val spec = VisualModelSpec(
        id = "test-model-1",
        baseUrl = "https://models.test/",
        model = ModelFile("model.tflite", modelBytes.size.toLong(), sha256(modelBytes)),
        vocabulary = ModelFile("tags.csv", vocabularyBytes.size.toLong(), sha256(vocabularyBytes)),
    )

    @After
    fun tearDown() = scope.cancel()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ok(bytes: ByteArray) = Response.Builder().code(200).message("OK").body(bytes.toResponseBody())

    private fun storeOver(client: OkHttpClient) = VisualModelStore(spec, folder.root, client, scope) { deleted.incrementAndGet() }

    private fun serving(model: ByteArray = modelBytes) = client { name -> ok(if (name == spec.model.name) model else vocabularyBytes) }

    private fun File.leftovers() = listFiles().orEmpty().map(File::getName).sorted()

    private fun VisualModelStore.awaitSettled(): ModelState = runBlocking {
        withTimeout(10_000) { states.first { it is ModelState.Ready || it is ModelState.Failed } }
    }

    @Test
    fun `download verifies both files, moves them into place and ends Ready (ADR 0101)`() {
        val store = storeOver(serving())
        assertEquals(ModelState.Absent, store.states.value)

        store.download()

        assertEquals(ModelState.Ready, store.awaitSettled())
        assertArrayEquals(modelBytes, store.modelFile.readBytes())
        assertArrayEquals(vocabularyBytes, store.vocabularyFile.readBytes())
        assertEquals(listOf("model.tflite", "tags.csv"), store.modelFile.parentFile!!.leftovers())
    }

    @Test
    fun `bytes that are not the pinned ones fail as a mismatch and leave no file behind (ADR 0101)`() {
        val tampered = modelBytes.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val store = storeOver(serving(model = tampered))

        store.download()

        assertEquals(ModelState.Failed(mismatch = true), store.awaitSettled())
        assertFalse(store.modelFile.exists())
        assertTrue(store.modelFile.parentFile!!.leftovers().none { it.endsWith(".part") || it == "model.tflite" })
    }

    @Test
    fun `a download that fails on its second file leaves neither file behind (ADR 0101)`() {
        val tampered = vocabularyBytes.copyOf().also { it[3] = (it[3] + 1).toByte() }
        val failures = listOf(ok(tampered) to true, Response.Builder().code(500).message("Server Error").body(ByteArray(0).toResponseBody()) to false)
        for ((second, mismatch) in failures) {
            val store = storeOver(client { name -> if (name == spec.model.name) ok(modelBytes) else second })

            store.download()

            assertEquals(ModelState.Failed(mismatch = mismatch), store.awaitSettled())
            assertFalse(store.modelFile.parentFile!!.exists())
        }
    }

    @Test
    fun `a file longer than pinned fails as a mismatch (ADR 0101)`() {
        val store = storeOver(serving(model = modelBytes + 1))

        store.download()

        assertEquals(ModelState.Failed(mismatch = true), store.awaitSettled())
        assertFalse(store.modelFile.exists())
    }

    @Test
    fun `an HTTP error fails without calling the bytes a mismatch (ADR 0101)`() {
        val store = storeOver(client { Response.Builder().code(500).message("Server Error").body(ByteArray(0).toResponseBody()) })

        store.download()

        assertEquals(ModelState.Failed(mismatch = false), store.awaitSettled())
        assertFalse(store.modelFile.exists())
    }

    @Test
    fun `a failed download can be started again (ADR 0101)`() {
        var healthy = false
        val store = storeOver(
            client { name ->
                if (healthy) ok(if (name == spec.model.name) modelBytes else vocabularyBytes)
                else Response.Builder().code(503).message("Unavailable").body(ByteArray(0).toResponseBody())
            },
        )
        store.download()
        assertEquals(ModelState.Failed(mismatch = false), store.awaitSettled())

        healthy = true
        store.download()

        assertEquals(ModelState.Ready, runBlocking { withTimeout(10_000) { store.states.first { it == ModelState.Ready } } })
    }

    @Test
    fun `a store over a folder holding both verified-size files starts Ready (ADR 0101)`() {
        val first = storeOver(serving())
        first.modelFile.parentFile!!.mkdirs()
        first.modelFile.writeBytes(modelBytes)
        first.vocabularyFile.writeBytes(vocabularyBytes)

        assertEquals(ModelState.Ready, storeOver(serving()).states.value)
    }

    @Test
    fun `a folder holding only one file starts Absent and is cleared (ADR 0101)`() {
        val first = storeOver(serving())
        first.modelFile.parentFile!!.mkdirs()
        first.modelFile.writeBytes(modelBytes)
        File(first.modelFile.parentFile, "tags.csv.part").writeBytes(vocabularyBytes.copyOf(4))

        assertEquals(ModelState.Absent, storeOver(serving()).states.value)
        assertFalse(first.modelFile.parentFile!!.exists())
    }

    @Test
    fun `a file cut short by an interrupted download does not start Ready (ADR 0101)`() {
        val first = storeOver(serving())
        first.modelFile.parentFile!!.mkdirs()
        first.modelFile.writeBytes(modelBytes.copyOf(1_000))
        first.vocabularyFile.writeBytes(vocabularyBytes)

        assertEquals(ModelState.Absent, storeOver(serving()).states.value)
    }

    @Test
    fun `files of the pinned sizes start Ready without being hashed again (ADR 0101)`() {
        val first = storeOver(serving())
        first.modelFile.parentFile!!.mkdirs()
        first.modelFile.writeBytes(ByteArray(modelBytes.size))
        first.vocabularyFile.writeBytes(ByteArray(vocabularyBytes.size))

        assertEquals(ModelState.Ready, storeOver(serving()).states.value)
    }

    @Test
    fun `delete after a download removes the folder and reports it (ADR 0101)`() = runBlocking {
        val store = storeOver(serving())
        store.download()
        store.awaitSettled()

        store.delete()

        assertEquals(ModelState.Absent, store.states.value)
        assertFalse(store.modelFile.parentFile!!.exists())
        assertEquals(1, deleted.get())
    }

    @Test
    fun `delete during a download stops it and nothing is left or restored (ADR 0101)`() = runBlocking {
        val reading = CountDownLatch(1)
        val slowSpec = spec.copy(model = spec.model.copy(bytes = 50_000_000L))
        val client = client { name ->
            Response.Builder().code(200).message("OK").body(
                if (name == slowSpec.model.name) StallingStream(reading).source().buffer().asResponseBody("application/octet-stream".toMediaType())
                else ByteArrayInputStream(vocabularyBytes).source().buffer().asResponseBody(),
            )
        }
        val store = VisualModelStore(slowSpec, folder.root, client, scope) { deleted.incrementAndGet() }
        store.download()
        assertTrue(reading.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("model.tflite.part"), store.modelFile.parentFile!!.leftovers())

        withTimeout(5_000) { store.delete() }

        assertEquals(ModelState.Absent, store.states.value)
        assertFalse(store.modelFile.parentFile!!.exists())
        assertEquals(1, deleted.get())
    }

    private fun client(serve: (name: String) -> Response.Builder): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                calls.set(chain.call())
                serve(chain.request().url.pathSegments.last())
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).build()
            },
        )
        .build()

    private val calls = AtomicReference<Call>()

    private inner class StallingStream(private val reading: CountDownLatch) : InputStream() {
        override fun read(): Int = error("unused")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reading.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STALL_SECONDS)
            while (calls.get()?.isCanceled() != true && System.nanoTime() < deadline) Thread.sleep(1)
            throw IOException("Canceled")
        }
    }

    private companion object {
        const val STALL_SECONDS = 20L
    }
}
