package com.theoriacodex.sources.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceJsonTest {
    private val record = Json.parseToJsonElement(
        """
        {"text": " a ", "int": 7, "fraction": -7.9, "exponent": 1e2, "huge": 2147483648, "numeric": " 12 ",
         "decimalText": "1.5", "flag": true, "null": null, "list": ["x"], "object": {"x": 1}}
        """,
    ).jsonObject

    @Test
    fun `reads any primitive as its text and nothing else`() {
        assertEquals(
            listOf(" a ", "7", "-7.9", "true", null, null, null, null),
            listOf("text", "int", "fraction", "flag", "null", "list", "object", "absent").map { key ->
                record[key].asStringOrNull()
            },
        )
    }

    @Test
    fun `reads integers from numbers and integer strings`() {
        assertEquals(
            listOf(7, -7, 100, null, 12, null, null, null, null, null),
            listOf("int", "fraction", "exponent", "huge", "numeric", "decimalText", "flag", "null", "list", "text")
                .map { key -> record[key].asIntOrNull() },
        )
    }
}
