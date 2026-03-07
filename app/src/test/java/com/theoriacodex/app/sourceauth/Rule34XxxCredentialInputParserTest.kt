package com.theoriacodex.app.sourceauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Rule34XxxCredentialInputParserTest {
    @Test
    fun `parses query fragment credentials`() {
        val parsed = parseRule34XxxCredentialInput("&api_key=abc123&user_id=42")

        requireNotNull(parsed)
        assertEquals("abc123", parsed.apiKey)
        assertEquals("42", parsed.userId)
    }

    @Test
    fun `parses full url credentials`() {
        val parsed = parseRule34XxxCredentialInput(
            "https://rule34.xxx/index.php?page=account&s=options&api_key=xyz&user_id=1001",
        )

        requireNotNull(parsed)
        assertEquals("xyz", parsed.apiKey)
        assertEquals("1001", parsed.userId)
    }

    @Test
    fun `returns null when pair is incomplete`() {
        assertNull(parseRule34XxxCredentialInput("&api_key=abc123"))
        assertNull(parseRule34XxxCredentialInput("&user_id=42"))
        assertNull(parseRule34XxxCredentialInput("abc123"))
    }
}
