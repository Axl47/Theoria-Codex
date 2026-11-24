package com.theoriacodex.data.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Serializable
    private data class NoteRecord(
        @SerialName("schemaVersion") val schemaVersion: Int = 1,
        @SerialName("text") val text: String = "",
    )

    @Test fun `an update is held in the form a later read of the file gives (ADR 0032)`() = runBlocking {
        val scope = newDataStoreScope()
        try {
            val store = openDecodedJsonDataStore(
                temporary.root.resolve("note.json"), "Note", 1, NoteRecord(), NoteRecord.serializer(), scope, FileStoreRecoveryRegistry(),
                decode = { it.text.trim() },
                encode = { NoteRecord(text = it) },
            )
            store.awaitReady()

            store.update { "  padded  " }

            assertEquals("padded", store.value())
        } finally {
            scope.closeDataStores()
        }
    }
}
