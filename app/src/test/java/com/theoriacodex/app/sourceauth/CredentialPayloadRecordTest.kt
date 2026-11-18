package com.theoriacodex.app.sourceauth

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.assertStoredFormat
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

    // The payload golden is the plaintext the Gson store encrypted. The envelope golden needs no entry here:
    // CredentialEnvelopeGoldenTest decrypts it, which reads every envelope key.
    @Test
    fun `the Gson-written payload holds every stored key`() {
        assertStoredFormat(
            golden("payload-gson-written.json") to CredentialPayloadRecord(
                pixivAccessToken = "access=token&<1>'",
                pixivRefreshToken = "refresh==",
                pixivExpiresAtEpochMs = 1_800_000_000_000L,
                gelbooruUserId = "1",
                gelbooruApiKey = "gel&key",
                rule34XxxUserId = "2",
                rule34XxxApiKey = "r34=key",
                customSites = mapOf(
                    "custom_safebooru_org" to CustomSiteCredentialRecord("42", "safe<key>"),
                    "custom_example_com" to CustomSiteCredentialRecord("u", "k"),
                ),
            ),
        )
    }

    private fun encode(record: CredentialPayloadRecord) = DurableJson.encodeToString(CredentialPayloadRecord.serializer(), record)

    private fun decode(json: String) = DurableJson.decodeFromString(CredentialPayloadRecord.serializer(), json)

    private fun golden(name: String) =
        checkNotNull(javaClass.getResource("/golden/sourceauth/$name")) { "missing golden $name" }.readText()
}
