package com.theoriacodex.app.sourceauth

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.assertGsonParity
import com.theoriacodex.domain.source.SiteCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The encrypted payload's plaintext shape (plan D7): custom-site keys are an additive map. */
class CredentialPayloadRecordTest {
    @Test
    fun `payloads without custom keys keep their previous bytes`() {
        val json = encode(CredentialPayloadRecord(SourceCredentialSnapshot(gelbooruUserId = "1", gelbooruApiKey = "k")))

        assertFalse(json.contains("customSites"))
    }

    @Test
    fun `custom keys round trip under customSites`() {
        val snapshot = SourceCredentialSnapshot(customSites = mapOf("custom_safebooru_org" to SiteCredentials("42", "key")))

        val json = encode(CredentialPayloadRecord(snapshot))
        val decoded = decode(json).toSnapshot()

        assertEquals(snapshot, decoded)
        assertEquals(true, json.contains(""""customSites":{"custom_safebooru_org":{"user":"42","apiKey":"key"}}"""))
    }

    @Test
    fun `malformed custom entries are dropped instead of failing the whole payload`() {
        val json = """{"schemaVersion":1,"gelbooruUserId":"1","gelbooruApiKey":"k","customSites":{
            "custom_ok_example":{"user":"a","apiKey":"b"},"PIXIV":{"user":"a","apiKey":"b"},
            "custom_blank_example":{"user":"","apiKey":"b"},"custom_null_example":null}}"""

        val decoded = decode(json).toSnapshot()

        assertEquals(mapOf("custom_ok_example" to SiteCredentials("a", "b")), decoded.customSites)
        assertEquals("1", decoded.gelbooruUserId)
    }

    // The payload golden is what the Gson store encrypted; the envelope golden is a Gson-written envelope, Base64
    // padding escaped as =.
    @Test
    fun `the kotlinx serializers match Gson on samples and goldens`() {
        assertGsonParity(
            samples = listOf(
                CredentialPayloadRecord(),
                CredentialPayloadRecord(
                    pixivAccessToken = "a=b&<c>'",
                    pixivRefreshToken = "r",
                    pixivExpiresAtEpochMs = 1L,
                    gelbooruUserId = "1",
                    gelbooruApiKey = "g",
                    rule34XxxUserId = "2",
                    rule34XxxApiKey = "x",
                    customSites = mapOf("custom_a_test" to CustomSiteCredentialRecord("u", "k")),
                ),
            ),
            goldenJson = listOf(golden("payload-gson-written.json")),
        )
        assertGsonParity(
            samples = listOf(CredentialEnvelopeRecord(), CredentialEnvelopeRecord(1, 1, "aXY=", "Y3Q=")),
            goldenJson = listOf(golden("envelope-gson-written.json")),
        )
    }

    private fun encode(record: CredentialPayloadRecord) = DurableJson.encodeToString(CredentialPayloadRecord.serializer(), record)

    private fun decode(json: String) = DurableJson.decodeFromString(CredentialPayloadRecord.serializer(), json)

    private fun golden(name: String) =
        checkNotNull(javaClass.getResource("/golden/sourceauth/$name")) { "missing golden $name" }.readText()
}
