package com.theoriacodex.app.codex.transfer

import com.theoriacodex.app.codex.CODEX_SHARE_MAX_BYTES
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
        assertEquals(true, runCatching { readBoundedTextDocument(CODEX_SHARE_MAX_BYTES) { byteArrayOf(0xC3.toByte(), 0x28).inputStream() } }.isFailure)
    }

    @Test
    fun `document provider opens off caller thread and exported text round trips`() = runBlocking {
        val caller = Thread.currentThread()
        val payload = ExportDocument("Saved", "saved.json", "{\"title\":\"Saved\"}")
        val file = writeExportDocument(folder.root.resolve("exports"), payload)
        val text = readBoundedTextDocument(CODEX_SHARE_MAX_BYTES) {
            assertNotEquals(caller, Thread.currentThread())
            file.inputStream()
        }
        assertEquals(payload.text, text)
        assertEquals(null, readBoundedTextDocument(CODEX_SHARE_MAX_BYTES) { null })
    }
}
