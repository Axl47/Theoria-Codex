package com.theoriacodex.data.storage

import com.google.gson.GsonBuilder
import com.google.gson.JsonSerializer
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.theoriacodex.data.testing.RecordingIoDispatcher
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
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

            assertEquals(Payload("fallback"), store.read(file, Payload("fallback")))
            store.write(file, Payload("stored"))
            assertEquals(Payload("stored"), store.read(file, Payload("fallback")))

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
            store.read(file, Payload("fallback"), logicalStore = "Test state") { recovery = it },
        )
        val recordedRecovery = requireNotNull(recovery)
        val quarantine = File(recordedRecovery.backupPath!!)
        assertFalse(file.exists())
        assertArrayEquals(original, quarantine.readBytes())
        assertEquals("Test state", recordedRecovery.logicalStore)
        assertEquals("state.json contains malformed JSON", recordedRecovery.reason)
        assertEquals(quarantine.absolutePath, recordedRecovery.quarantinePath)
        assertEquals(original.size.toLong(), recordedRecovery.byteCount)

        store.write(file, Payload("stable"))
        val partialTemp = tempFolder.root.resolve("state.json.interrupted.tmp")
        partialTemp.writeText("{partial")

        assertEquals(Payload("stable"), store.read(file, Payload("fallback")))
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
                AtomicJsonFileStore().read(file, Payload("fallback"), onRecovery = recoveries::add),
            )

            assertFalse(file.exists())
            assertEquals(1, recoveries.size)
            assertArrayEquals(body.toByteArray(), File(recoveries.single().backupPath!!).readBytes())
        }
    }

    @Test
    fun `matching quarantine completes interrupted recovery idempotently`() = runTest {
        val file = tempFolder.root.resolve("state.json")
        val bytes = "{broken".toByteArray()
        file.writeBytes(bytes)
        val quarantine = file.resolveSibling("${file.name}.corrupt-${bytes.size}-${bytes.sha256()}")
        quarantine.writeBytes(bytes)

        val recovered = AtomicJsonFileStore().read(file, Payload("fallback"))

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
            AtomicJsonFileStore().read(file, Payload("fallback"))
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
            gson = GsonBuilder().create(),
            fileOperations = object : RecordingAtomicFileOperations() {
                override fun writeAndSync(file: File, bytes: ByteArray) {
                    throw quarantineFailure
                }
            },
        )

        assertEquals(
            quarantineFailure,
            runCatching { failingQuarantine.read(quarantineFile, Payload("fallback")) }.exceptionOrNull(),
        )
        assertArrayEquals(malformed, quarantineFile.readBytes())

        val readFile = tempFolder.root.resolve("read-failure.json").apply { writeText("{}") }
        val readFailure = IOException("permission denied")
        val failingRead = AtomicJsonFileStore(
            ioDispatcher = Dispatchers.Unconfined,
            gson = GsonBuilder().create(),
            fileOperations = object : RecordingAtomicFileOperations() {
                override fun readBytes(file: File): ByteArray = throw readFailure
            },
        )
        assertEquals(
            readFailure,
            runCatching { failingRead.read(readFile, Payload("fallback")) }.exceptionOrNull(),
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
            gson = GsonBuilder().create(),
            fileOperations = object : RecordingAtomicFileOperations() {
                override fun moveWithoutReplace(tempFile: File, target: File) {
                    throw moveFailure
                }
            },
        )

        assertEquals(
            moveFailure,
            runCatching { store.read(file, Payload("fallback")) }.exceptionOrNull(),
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
        stableStore.write(file, Payload("stable"))
        val priorBody = file.readText()
        val failingGson = GsonBuilder()
            .registerTypeAdapter(
                Payload::class.java,
                JsonSerializer<Payload> { _, _, _ -> error("serialization failed") },
            )
            .create()
        val failingStore = AtomicJsonFileStore(gson = failingGson)

        val failure = runCatching { failingStore.write(file, Payload("replacement")) }.exceptionOrNull()

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
            gson = GsonBuilder().create(),
            fileOperations = operations,
        )
        val file = tempFolder.root.resolve("state.json")

        store.write(file, Payload("stored"))

        assertEquals(1, operations.atomicMoveCalls)
        assertEquals(1, operations.replacementCalls)
        assertEquals(Payload("stored"), store.read(file, Payload("fallback")))
    }

    @Test
    fun `ordinary move failure is surfaced without replacement and preserves prior file`() = runTest {
        val file = tempFolder.root.resolve("state.json")
        AtomicJsonFileStore().write(file, Payload("stable"))
        val priorBody = file.readText()
        val expected = IllegalStateException("move failed")
        val operations = RecordingAtomicFileOperations(atomicFailure = expected)
        val store = AtomicJsonFileStore(
            ioDispatcher = Dispatchers.Unconfined,
            gson = GsonBuilder().create(),
            fileOperations = operations,
        )

        val failure = runCatching { store.write(file, Payload("replacement")) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(expected.message, failure?.message)
        assertEquals(1, operations.atomicMoveCalls)
        assertEquals(0, operations.replacementCalls)
        assertEquals(priorBody, file.readText())
        assertFalse(tempFilesFor(file).any())
    }

    @Test
    fun `tolerant read does not swallow cancellation or unexpected adapter failures`() = runTest {
        val file = tempFolder.root.resolve("state.json")
        file.writeText("{}")
        val cancellation = CancellationException("cancelled")
        val cancellingStore = AtomicJsonFileStore(
            gson = GsonBuilder()
                .registerTypeAdapter(
                    Payload::class.java,
                    throwingReadAdapter(cancellation),
                )
                .create(),
        )
        val unexpected = IllegalArgumentException("adapter failed")
        val failingStore = AtomicJsonFileStore(
            gson = GsonBuilder()
                .registerTypeAdapter(
                    Payload::class.java,
                    throwingReadAdapter(unexpected),
                )
                .create(),
        )

        val cancellationFailure = runCatching {
            cancellingStore.read(file, Payload("fallback"))
        }.exceptionOrNull()
        assertTrue(cancellationFailure is CancellationException)
        assertEquals(cancellation.message, cancellationFailure?.message)
        val unexpectedFailure = runCatching {
            failingStore.read(file, Payload("fallback"))
        }.exceptionOrNull()
        assertTrue(unexpectedFailure is IllegalArgumentException)
        assertEquals(unexpected.message, unexpectedFailure?.message)
    }

    private fun throwingReadAdapter(failure: RuntimeException): TypeAdapter<Payload> {
        return object : TypeAdapter<Payload>() {
            override fun write(out: JsonWriter, value: Payload?) {
                out.nullValue()
            }

            override fun read(input: JsonReader): Payload {
                throw failure
            }
        }
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

    private data class Payload(
        val value: String,
    )
}
