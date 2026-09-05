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
