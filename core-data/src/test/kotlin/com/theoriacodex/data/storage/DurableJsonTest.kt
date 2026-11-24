package com.theoriacodex.data.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class DurableJsonTest {
    @Serializable
    private data class Count(@SerialName("n") val n: Int) {
        init {
            require(n >= 0) { "negative count" }
        }
    }

    @Test fun `a record check that fails while decoding a list element is not skipped (ADR 0033)`() {
        val elements = DurableJson.parseToJsonElement("""[{"n":1},{"n":"x"},{"n":-1}]""").jsonArray

        assertEquals("negative count", runCatching { elements.decodeEachOrSkip<Count>() }.exceptionOrNull()?.message)
    }
}
