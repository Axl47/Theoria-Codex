package com.theoriacodex.app.sourceauth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.domain.source.SiteCredentials
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Base64
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Corrupt credential files must read as "reconnect", not "temporarily unavailable", whatever the JSON library.
 * Robolectric has no Android Keystore, so a software one is installed for the duration of each test.
 */
@RunWith(RobolectricTestRunner::class)
class CredentialEnvelopeGoldenTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Before fun installKeystore() {
        FakeKeyStoreSpi.keys[CREDENTIAL_KEY_ALIAS] = key
        Security.addProvider(FakeKeystoreProvider)
        envelopeFile().delete()
    }

    @After fun removeKeystore() {
        Security.removeProvider(FakeKeystoreProvider.name)
        FakeKeyStoreSpi.keys.clear()
        envelopeFile().delete()
    }

    // Control: the encrypt helper and fake keystore are sound, so the payload failures below come from the JSON.
    @Test fun `well formed envelope around a valid payload reads successfully`() = runTest {
        writeEnvelope(envelopeAround("""{"schemaVersion":1,"gelbooruUserId":"1","gelbooruApiKey":"k"}"""))

        val read = newStore().read()

        assertEquals(
            CredentialEnvelopeReadResult.Success(SourceCredentialSnapshot(gelbooruUserId = "1", gelbooruApiKey = "k")),
            read,
        )
    }

    // An envelope the Gson store wrote and encrypted with this test's key decodes to every credential it held.
    @Test fun `envelope written by the Gson store reads every credential`() = runTest {
        writeEnvelope(golden("envelope-gson-written.json"))

        val read = newStore().read()

        assertEquals(
            CredentialEnvelopeReadResult.Success(
                SourceCredentialSnapshot(
                    pixivAccessToken = "access=token&<1>'",
                    pixivRefreshToken = "refresh==",
                    pixivExpiresAtEpochMs = 1_800_000_000_000L,
                    gelbooruUserId = "1",
                    gelbooruApiKey = "gel&key",
                    rule34XxxUserId = "2",
                    rule34XxxApiKey = "r34=key",
                    customSites = mapOf(
                        "custom_safebooru_org" to SiteCredentials("42", "safe<key>"),
                        "custom_example_com" to SiteCredentials("u", "k"),
                    ),
                ),
            ),
            read,
        )
    }

    // Pins: an envelope file that is not parseable JSON reads as ReconnectRequired.
    @Test fun `malformed envelope requires reconnect`() = runTest {
        writeEnvelope(golden("envelope-malformed.json"))

        assertEquals(CredentialEnvelopeReadResult.ReconnectRequired, newStore().read())
    }

    // Pins: an envelope that is valid JSON of the wrong shape (an array) reads as ReconnectRequired.
    @Test fun `wrong shape envelope requires reconnect`() = runTest {
        writeEnvelope(golden("envelope-wrong-shape.json"))

        assertEquals(CredentialEnvelopeReadResult.ReconnectRequired, newStore().read())
    }

    // Pins: a valid envelope whose decrypted payload is truncated JSON reads as ReconnectRequired.
    @Test fun `malformed payload requires reconnect`() = runTest {
        writeEnvelope(envelopeAround(golden("payload-malformed.json")))

        assertEquals(CredentialEnvelopeReadResult.ReconnectRequired, newStore().read())
    }

    // Pins: a valid envelope whose decrypted payload is valid JSON of the wrong shape reads as ReconnectRequired.
    @Test fun `wrong shape payload requires reconnect`() = runTest {
        writeEnvelope(envelopeAround(golden("payload-wrong-shape.json")))

        assertEquals(CredentialEnvelopeReadResult.ReconnectRequired, newStore().read())
    }

    private fun newStore() = AndroidCredentialEnvelopeStore(context, relativeEnvelopePath = RELATIVE_PATH)

    private fun envelopeFile() = File(context.noBackupFilesDir, RELATIVE_PATH)

    private fun writeEnvelope(json: String) {
        envelopeFile().apply { parentFile?.mkdirs() }.writeText(json)
    }

    /** Encrypts [payload] exactly as the store does (same key, AES-GCM, package-bound AAD) inside a v1 envelope. */
    private fun envelopeAround(payload: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD("${context.packageName}|source-credentials|format-1".toByteArray())
        }
        val ciphertext = cipher.doFinal(payload.toByteArray())
        val encoder = Base64.getEncoder()
        return """{"formatVersion":1,"keyVersion":1,"iv":"${encoder.encodeToString(cipher.iv)}",""" +
            """"ciphertext":"${encoder.encodeToString(ciphertext)}"}"""
    }

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/golden/sourceauth/$name")) { "missing golden $name" }.readText()
}

private const val RELATIVE_PATH = "golden-test/credentials.json"

private object FakeKeystoreProvider : Provider("AndroidKeyStore", 1.0, "In-memory test keystore") {
    init {
        put("KeyStore.AndroidKeyStore", FakeKeyStoreSpi::class.java.name)
    }
}

/** Holds secret keys by alias for the fake Android Keystore; every `KeyStore.getInstance` shares [keys]. */
class FakeKeyStoreSpi : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
    override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
    override fun engineDeleteEntry(alias: String) {
        keys.remove(alias)
    }
    override fun engineSize(): Int = keys.size
    override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineGetCreationDate(alias: String): Date? = null
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) {
        keys[alias] = key
    }
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) = Unit
    override fun engineSetCertificateEntry(alias: String, cert: Certificate?) = Unit
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit

    companion object {
        val keys = java.util.concurrent.ConcurrentHashMap<String, Key>()
    }
}
