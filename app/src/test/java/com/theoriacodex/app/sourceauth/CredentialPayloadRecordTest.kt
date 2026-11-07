package com.theoriacodex.app.sourceauth

import com.google.gson.Gson
import com.theoriacodex.domain.source.SiteCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The encrypted payload's plaintext shape (plan D7): custom-site keys are an additive map. */
class CredentialPayloadRecordTest {
    private val gson = Gson()

    @Test
    fun `payloads without custom keys keep their previous bytes`() {
        val json = gson.toJson(CredentialPayloadRecord(SourceCredentialSnapshot(gelbooruUserId = "1", gelbooruApiKey = "k")))

        assertFalse(json.contains("customSites"))
    }

    @Test
    fun `custom keys round trip under customSites`() {
        val snapshot = SourceCredentialSnapshot(customSites = mapOf("custom_safebooru_org" to SiteCredentials("42", "key")))

        val json = gson.toJson(CredentialPayloadRecord(snapshot))
        val decoded = gson.fromJson(json, CredentialPayloadRecord::class.java).toSnapshot()

        assertEquals(snapshot, decoded)
        assertEquals(true, json.contains(""""customSites":{"custom_safebooru_org":{"user":"42","apiKey":"key"}}"""))
    }

    @Test
    fun `malformed custom entries are dropped instead of failing the whole payload`() {
        val json = """{"schemaVersion":1,"gelbooruUserId":"1","gelbooruApiKey":"k","customSites":{
            "custom_ok_example":{"user":"a","apiKey":"b"},"PIXIV":{"user":"a","apiKey":"b"},
            "custom_blank_example":{"user":"","apiKey":"b"},"custom_null_example":null}}"""

        val decoded = gson.fromJson(json, CredentialPayloadRecord::class.java).toSnapshot()

        assertEquals(mapOf("custom_ok_example" to SiteCredentials("a", "b")), decoded.customSites)
        assertEquals("1", decoded.gelbooruUserId)
    }
}
