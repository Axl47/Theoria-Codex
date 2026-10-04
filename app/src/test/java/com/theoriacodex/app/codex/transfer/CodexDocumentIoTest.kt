package com.theoriacodex.app.codex.transfer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodexDocumentIoTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `shared document reader consumes at most one excess byte and rejects malformed UTF8`() = runBlocking {
        val stream = java.io.ByteArrayInputStream(ByteArray(100))
        assertEquals(true, runCatching { com.theoriacodex.data.repository.readBoundedDocument(stream, 16) }.isFailure)
        assertEquals(83, stream.available())
        assertEquals(true, runCatching { readCodexDocument { byteArrayOf(0xC3.toByte(), 0x28).inputStream() } }.isFailure)
    }

    @Test
    fun `document provider opens off caller thread and exported text round trips`() = runBlocking {
        val caller = Thread.currentThread()
        val payload = CodexExportPayload("Saved", "saved.json", "{\"title\":\"Saved\"}")
        val file = writeCodexDocument(folder.root.resolve("exports"), payload)
        val text = readCodexDocument {
            assertNotEquals(caller, Thread.currentThread())
            file.inputStream()
        }
        assertEquals(payload.json, text)
        assertEquals(null, readCodexDocument { null })
    }
}
