package com.theoriacodex.app.sourceauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GelbooruCredentialInputParserTest {
    @Test
    fun `parses query fragment credentials`() {
        val parsed = parseGelbooruCredentialInput("&api_key=abc123&user_id=42")

        requireNotNull(parsed)
        assertEquals("abc123", parsed.apiKey)
        assertEquals("42", parsed.userId)
    }

    @Test
    fun `parses full url credentials`() {
        val parsed = parseGelbooruCredentialInput(
            "https://gelbooru.com/index.php?page=account&s=options&api_key=xyz&user_id=1001",
        )

        requireNotNull(parsed)
        assertEquals("xyz", parsed.apiKey)
        assertEquals("1001", parsed.userId)
    }

    @Test
    fun `returns null when pair is incomplete`() {
        assertNull(parseGelbooruCredentialInput("&api_key=abc123"))
        assertNull(parseGelbooruCredentialInput("&user_id=42"))
        assertNull(parseGelbooruCredentialInput("abc123"))
    }
}
