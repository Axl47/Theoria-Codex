package com.theoriacodex.app.sourceauth

import com.theoriacodex.domain.source.SiteCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialSnippetParserTest {
    @Test
    fun `parses a Gelbooru-family query fragment`() {
        assertEquals(SiteCredentials("42", "abc123"), parseCredentialSnippet("&api_key=abc123&user_id=42"))
    }

    @Test
    fun `parses a full account url`() {
        assertEquals(
            SiteCredentials("1001", "xyz"),
            parseCredentialSnippet("https://rule34.xxx/index.php?page=account&s=options&api_key=xyz&user_id=1001"),
        )
    }

    @Test
    fun `parses a Danbooru-family login pair and decodes values`() {
        assertEquals(SiteCredentials("some user", "k3y"), parseCredentialSnippet("login=some%20user&api_key=k3y"))
    }

    @Test
    fun `returns null when the pair is incomplete`() {
        assertNull(parseCredentialSnippet("&api_key=abc123"))
        assertNull(parseCredentialSnippet("login=name"))
        assertNull(parseCredentialSnippet("abc123"))
    }
}
