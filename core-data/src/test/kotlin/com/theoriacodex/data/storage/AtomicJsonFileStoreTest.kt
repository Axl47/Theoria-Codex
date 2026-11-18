package com.theoriacodex.data.storage

import com.theoriacodex.data.testing.RecordingIoDispatcher
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicJsonFileStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `read and atomic replacement run on the injected IO lane`() = runTest {
        RecordingIoDispatcher("atomic-json-io").use { dispatcher ->
            val store = AtomicJsonFileStore(ioDispatcher = dispatcher)
            val file = tempFolder.root.resolve("nested/state.json")

            assertEquals(Payload("fallback"), store.read(file, Payload("fallback"), Payload.serializer()))
            store.write(file, Payload("stored"), Payload.serializer())
            assertEquals(Payload("stored"), store.read(file, Payload("fallback"), Payload.serializer()))

            assertTrue(dispatcher.executionThreadNames.isNotEmpty())
            assertTrue(dispatcher.executionThreadNames.all { name -> name == "atomic-json-io" })
        }
    }

    @Test
    fun `malformed target is verified in quarantine before fallback and later writes preserve it`() = runTest {
        val store = AtomicJsonFileStore()
        val file = tempFolder.root.resolve("state.json")
        val original = "{broken".toByteArray()
        file.writeBytes(original)
        var recovery: CorruptionRecovery? = null

        assertEquals(
            Payload("fallback"),
            store.read(file, Payload("fallback"), Payload.serializer(), logicalStore = "Test state") { recovery = it },
        )
        val recordedRecovery = requireNotNull(recovery)
        val quarantine = File(recordedRecovery.backupPath!!)
        assertFalse(file.exists())
        assertArrayEquals(original, quarantine.readBytes())
        assertEquals("Test state", recordedRecovery.logicalStore)
        assertEquals("state.json contains malformed JSON", recordedRecovery.reason)
        assertEquals(original.size.toLong(), recordedRecovery.byteCount)

        store.write(file, Payload("stable"), Payload.serializer())
        val partialTemp = tempFolder.root.resolve("state.json.interrupted.tmp")
        partialTemp.writeText("{partial")

        assertEquals(Payload("stable"), store.read(file, Payload("fallback"), Payload.serializer()))
        assertArrayEquals(original, quarantine.readBytes())
        assertTrue(partialTemp.exists())
    }

    @Test
    fun `empty and null targets are recovery events`() = runTest {
        listOf("", "  \n", "null").forEachIndexed { index, body ->
            val file = tempFolder.root.resolve("state-$index.json")
            file.writeText(body)
            val recoveries = mutableListOf<CorruptionRecovery>()

            assertEquals(
                Payload("fallback"),
                AtomicJsonFileStore().read(file, Payload("fallback"), Payload.serializer(), onRecovery = recoveries::add),
            )

            assertFalse(file.exists())
            assertEquals(1, recoveries.size)
            assertArrayEquals(body.toByteArray(), File(recoveries.single().backupPath!!).readBytes())
        }
    }

    @Test
    fun `malformed, wrong-shaped, null, blank, and non-UTF-8 files are quarantined`() = runTest {
        val store = AtomicJsonFileStore()
        val cases = mapOf(
            "{broken".toByteArray() to "contains malformed JSON",
            "[]".toByteArray() to "contains malformed JSON",
            """{"value":null}""".toByteArray() to "contains malformed JSON",
            " null ".toByteArray() to "contains a null JSON value",
            "  \n".toByteArray() to "is empty",
            byteArrayOf(0xC3.toByte(), 0x28) to "is not valid UTF-8 JSON",
        )
        cases.entries.forEachIndexed { index, (bytes, reason) ->
            val file = tempFolder.root.resolve("kotlinx-$index.json").apply { writeBytes(bytes) }
            val recoveries = mutableListOf<CorruptionRecovery>()

            assertEquals(
                Payload("fallback"),
                store.read(file, Payload("fallback"), Payload.serializer(), onRecovery = recoveries::add),
            )

            assertFalse(file.exists())
            assertEquals("${file.name} $reason", recoveries.single().reason)
            assertArrayEquals(bytes, File(recoveries.single().backupPath!!).readBytes())
        }
    }

    @Test
    fun `writes read back unescaped and other decoding failures propagate untouched`() = runTest {
        val store = AtomicJsonFileStore()
        val file = tempFolder.root.resolve("nested/kotlinx.json")
        store.write(file, Payload("a & b"), Payload.serializer())
        assertEquals("""{"value":"a & b"}""", file.readText())
        assertEquals(
            Payload("a & b"),
            store.read(file, Payload("fallback"), Payload.serializer()),
        )

        val rejecting = object : KSerializer<Payload> by Payload.serializer() {
            override fun deserialize(decoder: Decoder): Payload = throw IllegalArgumentException("adapter failed")
        }
        val failure = runCatching { store.read(file, Payload("fallback"), rejecting) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class, failure?.let { it::class })
        assertEquals("""{"value":"a & b"}""", file.readText())
    }

    @Test
    fun `matching quarantine completes interrupted recovery idempotently`() = runTest {
        val file = tempFolder.root.resolve("state.json")
        val bytes = "{broken".toByteArray()
        file.writeBytes(bytes)
        val quarantine = file.resolveSibling("${file.name}.corrupt-${bytes.size}-${bytes.sha256()}")
        quarantine.writeBytes(bytes)

        val recovered = AtomicJsonFileStore().read(file, Payload("fallback"), Payload.serializer())

        assertEquals(Payload("fallback"), recovered)
        assertFalse(file.exists())
        assertArrayEquals(bytes, quarantine.readBytes())
    }

    @Test
    fun `different quarantine collision fails closed with live bytes untouched`() = runTest {
        val file = tempFolder.root.resolve("state.json")
        val bytes = "{broken".toByteArray()
        file.writeBytes(bytes)
        val quarantine = file.resolveSibling("${file.name}.corrupt-${bytes.size}-${bytes.sha256()}")
        quarantine.writeText("different")

        val failure = runCatching {
            AtomicJsonFileStore().read(file, Payload("fallback"), Payload.serializer())
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("different", quarantine.readText())
    }

    @Test
    fun `quarantine failure and ordinary read IO failure propagate without changing live bytes`() = runTest {
        val quarantineFile = tempFolder.root.resolve("quarantine-failure.json")
        val malformed = "{broken".toByteArray()
        quarantineFile.writeBytes(malformed)
        val quarantineFailure = IOException("quarantine unavailable")
        val failingQuarantine = AtomicJsonFileStore(
            ioDispatcher = Dispatchers.Unconfined,
            fileOperations = object : RecordingAtomicFileOperations() {
                override fun writeAndSync(file: File, bytes: ByteArray) {
                    throw quarantineFailure
                }
            },
        )

        assertEquals(
            quarantineFailure,
            runCatching { failingQuarantine.read(quarantineFile, Payload("fallback"), Payload.serializer()) }.exceptionOrNull(),
        )
        assertArrayEquals(malformed, quarantineFile.readBytes())

        val readFile = tempFolder.root.resolve("read-failure.json").apply { writeText("{}") }
        val readFailure = IOException("permission denied")
        val failingRead = AtomicJsonFileStore(
            ioDispatcher = Dispatchers.Unconfined,
            fileOperations = object : RecordingAtomicFileOperations() {
                override fun readBytes(file: File): ByteArray = throw readFailure
            },
        )
        assertEquals(
            readFailure,
            runCatching { failingRead.read(readFile, Payload("fallback"), Payload.serializer()) }.exceptionOrNull(),
        )
        assertTrue(readFile.exists())
    }

    @Test
    fun `quarantine move failure leaves live bytes untouched`() = runTest {
        val file = tempFolder.root.resolve("move-failure.json")
        val malformed = "{broken".toByteArray()
        file.writeBytes(malformed)
        val moveFailure = IOException("move unavailable")
        val store = AtomicJsonFileStore(
            ioDispatcher = Dispatchers.Unconfined,
            fileOperations = object : RecordingAtomicFileOperations() {
                override fun moveWithoutReplace(tempFile: File, target: File) {
                    throw moveFailure
                }
            },
        )

        assertEquals(
            moveFailure,
            runCatching { store.read(file, Payload("fallback"), Payload.serializer()) }.exceptionOrNull(),
        )
        assertArrayEquals(malformed, file.readBytes())
        assertFalse(file.parentFile.listFiles().orEmpty().any { candidate ->
            candidate.name.startsWith("${file.name}.corrupt-")
        })
    }

    @Test
    fun `serialization failure preserves the prior file and cleans its temp file`() = runTest {
        val file = tempFolder.root.resolve("state.json")
        val stableStore = AtomicJsonFileStore()
        stableStore.write(file, Payload("stable"), Payload.serializer())
        val priorBody = file.readText()
        val failing = object : KSerializer<Payload> by Payload.serializer() {
            override fun serialize(encoder: Encoder, value: Payload) = error("serialization failed")
        }
        val failure = runCatching { stableStore.write(file, Payload("replacement"), failing) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(priorBody, file.readText())
        assertFalse(
            tempFolder.root.listFiles().orEmpty().any { candidate ->
                candidate.name.startsWith("state.json.") && candidate.name.endsWith(".tmp")
            }
        )
    }

    @Test
    fun `unsupported atomic move uses replacement fallback`() = runTest {
        val operations = RecordingAtomicFileOperations(
            atomicFailure = AtomicMoveNotSupportedException("temp", "target", "unsupported"),
        )
        val store = AtomicJsonFileStore(
            ioDispatcher = Dispatchers.Unconfined,
            fileOperations = operations,
        )
        val file = tempFolder.root.resolve("state.json")

        store.write(file, Payload("stored"), Payload.serializer())

        assertEquals(1, operations.atomicMoveCalls)
        assertEquals(1, operations.replacementCalls)
        assertEquals(Payload("stored"), store.read(file, Payload("fallback"), Payload.serializer()))
    }

    @Test
    fun `ordinary move failure is surfaced without replacement and preserves prior file`() = runTest {
        val file = tempFolder.root.resolve("state.json")
        AtomicJsonFileStore().write(file, Payload("stable"), Payload.serializer())
        val priorBody = file.readText()
        val expected = IllegalStateException("move failed")
        val operations = RecordingAtomicFileOperations(atomicFailure = expected)
        val store = AtomicJsonFileStore(
            ioDispatcher = Dispatchers.Unconfined,
            fileOperations = operations,
        )

        val failure = runCatching { store.write(file, Payload("replacement"), Payload.serializer()) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(expected.message, failure?.message)
        assertEquals(1, operations.atomicMoveCalls)
        assertEquals(0, operations.replacementCalls)
        assertEquals(priorBody, file.readText())
        assertFalse(tempFilesFor(file).any())
    }

    @Test
    fun `read does not swallow cancellation`() = runTest {
        val file = tempFolder.root.resolve("state.json")
        file.writeText("{}")
        val cancellation = CancellationException("cancelled")
        val cancelling = object : KSerializer<Payload> by Payload.serializer() {
            override fun deserialize(decoder: Decoder): Payload = throw cancellation
        }

        val failure = runCatching { AtomicJsonFileStore().read(file, Payload("fallback"), cancelling) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(cancellation.message, failure?.message)
        assertTrue(file.exists())
    }

    @Test
    fun `intent sync failure and oversized UTF8 cannot replace the previous committed record`() = runTest {
        val file = tempFolder.root.resolve("intent.json")
        file.writeText("previous")
        var syncCalls = 0
        val operations = object : RecordingAtomicFileOperations() {
            override fun writeAndSync(file: File, bytes: ByteArray) {
                syncCalls++
                throw IOException("fsync failed")
            }
        }
        val store = AtomicJsonFileStore(Dispatchers.Unconfined, operations)
        assertTrue(runCatching { store.writeIntent(file, Payload("next"), Payload.serializer(), maxBytes = 100) }.exceptionOrNull() is IOException)
        assertEquals(1, syncCalls)
        assertEquals(0, operations.atomicMoveCalls)
        assertEquals("previous", file.readText())
        assertTrue(runCatching { store.writeIntent(file, Payload("🙂".repeat(20)), Payload.serializer(), maxBytes = 32) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, syncCalls)
        assertEquals("previous", file.readText())
        assertTrue(tempFilesFor(file).none())
    }

    @Test
    fun `intent admission follows file sync replacement and directory sync including fallback`() = runTest {
        for (fallback in listOf(false, true)) {
            val events = mutableListOf<String>()
            val operations = object : RecordingAtomicFileOperations(
                atomicFailure = if (fallback) AtomicMoveNotSupportedException("temp", "target", "test") else null,
            ) {
                override fun writeAndSync(file: File, bytes: ByteArray) {
                    super.writeAndSync(file, bytes)
                    events += "file sync"
                }
                override fun moveAtomically(tempFile: File, target: File) {
                    events += "move"
                    super.moveAtomically(tempFile, target)
                }
                override fun replace(tempFile: File, target: File) {
                    events += "replace"
                    super.replace(tempFile, target)
                }
                override fun syncDirectory(directory: File) {
                    super.syncDirectory(directory)
                    events += "directory sync"
                }
            }
            val store = AtomicJsonFileStore(Dispatchers.Unconfined, operations)
            store.writeIntent(tempFolder.root.resolve("intent-$fallback.json"), Payload("ready"), Payload.serializer(), 100)
            events += "admitted"
            assertEquals(listOfNotNull("file sync", "move", "replace".takeIf { fallback }, "directory sync", "admitted"), events)
        }
    }

    @Test
    fun `first intent also commits each newly created directory entry`() = runTest {
        val synced = mutableListOf<File>()
        val operations = object : RecordingAtomicFileOperations() {
            override fun syncDirectory(directory: File) {
                super.syncDirectory(directory)
                synced += directory
            }
        }
        val file = tempFolder.root.resolve("new/nested/intent.json")
        AtomicJsonFileStore(Dispatchers.Unconfined, operations)
            .writeIntent(file, Payload("ready"), Payload.serializer(), 100)
        assertEquals(listOf(file.parentFile, file.parentFile.parentFile, tempFolder.root), synced)
    }

    @Test
    fun `directory sync failure prevents admission while ordinary writes retain their policy`() = runTest {
        val failure = IOException("directory fsync failed")
        val operations = object : RecordingAtomicFileOperations() {
            override fun syncDirectory(directory: File) { throw failure }
        }
        val store = AtomicJsonFileStore(Dispatchers.Unconfined, operations)
        val file = tempFolder.root.resolve("intent.json")
        var admitted = false
        val actual = runCatching {
            store.writeIntent(file, Payload("ready"), Payload.serializer(), 100)
            admitted = true
        }.exceptionOrNull()
        assertTrue(actual is IOException)
        assertEquals(failure.message, actual?.message)
        assertFalse(admitted)
        assertTrue(tempFilesFor(file).none())
        store.write(file, Payload("ordinary"), Payload.serializer())
        assertEquals(Payload("ordinary"), store.read(file, Payload("fallback"), Payload.serializer()))
    }

    private fun tempFilesFor(file: File): Sequence<File> {
        return file.parentFile.listFiles().orEmpty().asSequence().filter { candidate ->
            candidate.name.startsWith("${file.name}.") && candidate.name.endsWith(".tmp")
        }
    }

    private open class RecordingAtomicFileOperations(
        private val atomicFailure: Throwable? = null,
    ) : AtomicFileOperations {
        var atomicMoveCalls: Int = 0
            private set
        var replacementCalls: Int = 0
            private set

        override fun createTempFile(target: File): File {
            return File.createTempFile("${target.name}.", ".tmp", target.parentFile)
        }

        override fun moveAtomically(tempFile: File, target: File) {
            atomicMoveCalls += 1
            atomicFailure?.let { failure -> throw failure }
            Files.move(tempFile.toPath(), target.toPath(), REPLACE_EXISTING)
        }

        override fun replace(tempFile: File, target: File) {
            replacementCalls += 1
            Files.move(tempFile.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }

    @Serializable
    private data class Payload(
        @SerialName("value") val value: String,
    )
}
