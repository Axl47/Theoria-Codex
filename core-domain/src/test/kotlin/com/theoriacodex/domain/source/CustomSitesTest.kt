package com.theoriacodex.domain.source

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSitesTest {
    @Test
    fun `addresses normalize to an HTTPS root and derive the planned ids`() {
        val cases = listOf(
            Triple("safebooru.org", "https://safebooru.org", "custom_safebooru_org"),
            Triple(
                "https://www.Example-Booru.net/index.php?page=post&s=list",
                "https://www.example-booru.net",
                "custom_example-booru_net",
            ),
            Triple(
                "https://boards.example.com/booru/posts?tags=x",
                "https://boards.example.com/booru",
                "custom_boards_example_com__h${sha256("/booru").take(8)}",
            ),
            Triple("https://bücher.example", "https://xn--bcher-kva.example", "custom_xn--bcher-kva_example"),
            Triple("https://booru.example:8443/", "https://booru.example:8443", "custom_booru_example__p8443"),
            Triple("HTTPS://Booru.Example:443/#top", "https://booru.example", "custom_booru_example"),
            Triple("//booru.example//posts/123", "https://booru.example", "custom_booru_example"),
        )

        cases.forEach { (input, baseUrl, sourceId) ->
            val address = valid(input)
            assertEquals(input, baseUrl, address.baseUrl)
            assertEquals(input, sourceId, address.derivedSourceId)
            assertTrue(input, CustomSourceIds.isValid(address.derivedSourceId))
        }
    }

    @Test
    fun `install paths keep their case and separate sites on one host`() {
        val first = valid("https://example.com/Board-A/post/1")
        val second = valid("https://example.com/board-b")

        assertEquals("https://example.com/Board-A", first.baseUrl)
        assertEquals("/Board-A", first.path)
        assertNotEquals(first.derivedSourceId, second.derivedSourceId)
        assertEquals(valid("https://example.com/").derivedSourceId, "custom_example_com")
    }

    @Test
    fun `unusable addresses are rejected before any request`() {
        val cases = mapOf(
            "" to SiteAddressError.MALFORMED,
            "   " to SiteAddressError.MALFORMED,
            "https://" to SiteAddressError.MALFORMED,
            "https://user:secret@booru.example" to SiteAddressError.MALFORMED,
            "https://booru.example:0" to SiteAddressError.MALFORMED,
            "https://booru.example:70000" to SiteAddressError.MALFORMED,
            "https://booru.example:" to SiteAddressError.MALFORMED,
            "http://safebooru.org" to SiteAddressError.NOT_HTTPS,
            "ftp://safebooru.org" to SiteAddressError.NOT_HTTPS,
            "https://[::1]/" to SiteAddressError.UNSUPPORTED_HOST,
            "https://bad_host.example" to SiteAddressError.UNSUPPORTED_HOST,
            "https://-booru.example" to SiteAddressError.UNSUPPORTED_HOST,
            "https://${"a".repeat(60)}.${"b".repeat(40)}.example" to SiteAddressError.TOO_LONG,
            "https://booru.example/${"x".repeat(2_100)}" to SiteAddressError.TOO_LONG,
        )

        cases.forEach { (input, error) ->
            assertEquals(input.take(40), SiteAddressParse.Invalid(error), SiteAddress.parse(input))
        }
    }

    @Test
    fun `id validation accepts only the custom alphabet`() {
        listOf(
            "custom_safebooru_org",
            "custom_example-booru_net",
            "custom_192_168_1_10",
            "custom_booru_example__p8443",
            "custom_booru_example__h0a1b2c3d",
            "custom_booru_example__p8443__h0a1b2c3d",
        ).forEach { assertTrue(it, CustomSourceIds.isValid(it)) }

        listOf(
            "PIXIV",
            "custom_",
            "custom:safebooru.org",
            "custom_safebooru.org",
            "CUSTOM_SAFEBOORU_ORG",
            "custom_-booru_org",
            "custom_booru__p0",
            "custom_booru__hXYZ",
            "custom_booru___org",
            "custom_${"a".repeat(CustomSourceIds.MAX_LENGTH)}",
        ).forEach { assertFalse(it, CustomSourceIds.isValid(it)) }
    }

    @Test
    fun `host mapping is injective and reversible for labels`() {
        val hosts = listOf("a-b.com", "a.b-com", "ab.com", "a.bcom", "a.b.com", "a-b-com.net")
        val ids = hosts.map { CustomSourceIds.fromAddress(it, port = null, path = "") }

        assertEquals(hosts.size, ids.toSet().size)
        hosts.zip(ids).forEach { (host, id) -> assertEquals(host, CustomSourceIds.hostLabel(id)) }
        assertEquals("booru.example", CustomSourceIds.hostLabel("custom_booru_example__p8443__h0a1b2c3d"))
        assertEquals(
            CustomSourceIds.fromAddress("example.com", null, ""),
            CustomSourceIds.fromAddress("www.example.com", null, ""),
        )
    }

    @Test
    fun `profiles validate identity, label and headers and keep unknown families`() {
        val address = valid("safebooru.org")
        val profile = SiteProfile(
            sourceId = address.derivedSourceId,
            address = address,
            label = "Safebooru",
            familyId = "MOEBOORU",
            addedAtEpochMs = 1L,
        )

        assertNull(profile.family)
        assertEquals(ProtocolFamily.GELBOORU_02, profile.copy(familyId = "GELBOORU_02").family)
        assertRejected { profile.copy(sourceId = "PIXIV") }
        assertRejected { profile.copy(label = " Safebooru") }
        assertRejected { profile.copy(label = "x".repeat(SiteProfile.MAX_LABEL_LENGTH + 1)) }
        assertRejected { profile.copy(searchTermLimit = 0) }
        assertRejected { SiteHeaders(userAgent = "Theoria\r\nX-Injected: 1") }
        assertRejected { SiteCredentials(user = "", apiKey = "key") }
        assertEquals("SiteCredentials([REDACTED])", SiteCredentials("user", "secret-key").toString())
    }

    private fun valid(input: String): SiteAddress {
        val parsed = SiteAddress.parse(input)
        check(parsed is SiteAddressParse.Valid) { "$input → $parsed" }
        return parsed.address
    }

    private fun assertRejected(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
