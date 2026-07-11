package com.theoriacodex.app.sourceauth

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCredentialEnvelopeStoreDeviceTest {
    private lateinit var context: Context
    private lateinit var store: AndroidCredentialEnvelopeStore

    @Before
    fun setUp() {
        runBlocking {
            context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            store = newStore()
            store.reset()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            store.reset()
        }
    }

    @Test
    fun constructionDoesNotCreateAFileOrKeystoreEntry() {
        val constructed = newStore()

        assertFalse(envelopeFile().exists())
        assertFalse(keyStore().containsAlias(TEST_KEY_ALIAS))
        // Keep the local reference live so this also guards against eager delegated properties.
        assertEquals(AndroidCredentialEnvelopeStore::class, constructed::class)
    }

    @Test
    fun wholeSnapshotRoundTripsThroughAndroidKeystoreWithoutPlaintextOnDisk() = runBlocking {
        val snapshot = populatedSnapshot()

        assertEquals(CredentialStoreWriteResult.Success, store.writeVerified(snapshot))

        assertEquals(
            setOf("formatVersion", "keyVersion", "iv", "ciphertext"),
            JSONObject(envelopeFile().readText()).keySetCompat(),
        )
        val read = store.read()
        assertTrue(read is CredentialEnvelopeReadResult.Success)
        assertEquals(snapshot, (read as CredentialEnvelopeReadResult.Success).snapshot)
        assertFalse(envelopeFile().readText().contains("device-private-access"))
        assertFalse(read.toString().contains("device-private-access"))
    }

    @Test
    fun tamperedCiphertextRequiresReconnectAndPreservesEvidence() = runBlocking {
        assertEquals(CredentialStoreWriteResult.Success, store.writeVerified(populatedSnapshot()))
        val file = envelopeFile()
        val record = JSONObject(file.readText())
        val ciphertext = record.getString("ciphertext")
        val replacement = if (ciphertext.first() == 'A') 'B' else 'A'
        record.put("ciphertext", replacement + ciphertext.drop(1))
        file.writeText(record.toString())

        assertEquals(CredentialEnvelopeReadResult.ReconnectRequired, store.read())
        assertTrue("corruption is not deleted automatically", file.exists())
        assertTrue("the key is preserved until explicit reset", keyStore().containsAlias(TEST_KEY_ALIAS))
    }

    @Test
    fun missingAliasRequiresReconnectWithoutDeletingTheEnvelope() = runBlocking {
        assertEquals(CredentialStoreWriteResult.Success, store.writeVerified(populatedSnapshot()))
        keyStore().deleteEntry(TEST_KEY_ALIAS)

        assertEquals(CredentialEnvelopeReadResult.ReconnectRequired, store.read())
        assertTrue(envelopeFile().exists())
    }

    @Test
    fun interruptedAtomicWriteBackupRemainsReadableAndResettable() = runBlocking {
        val snapshot = populatedSnapshot()
        assertEquals(CredentialStoreWriteResult.Success, store.writeVerified(snapshot))
        val file = envelopeFile()
        val backup = File(file.path + ".bak")
        assertTrue(file.renameTo(backup))
        assertFalse(file.exists())

        val read = store.read()
        assertTrue(read is CredentialEnvelopeReadResult.Success)
        assertEquals(snapshot, (read as CredentialEnvelopeReadResult.Success).snapshot)

        assertTrue(store.reset())
        assertFalse(file.exists())
        assertFalse(backup.exists())
        assertFalse(keyStore().containsAlias(TEST_KEY_ALIAS))
    }

    private fun newStore(): AndroidCredentialEnvelopeStore = AndroidCredentialEnvelopeStore(
        context = context,
        relativeEnvelopePath = TEST_ENVELOPE_PATH,
        keyAlias = TEST_KEY_ALIAS,
    )

    private fun envelopeFile(): File = File(context.noBackupFilesDir, TEST_ENVELOPE_PATH)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun populatedSnapshot(): SourceCredentialSnapshot = SourceCredentialSnapshot(
        pixivAccessToken = "device-private-access",
        pixivRefreshToken = "device-private-refresh",
        pixivExpiresAtEpochMs = 4_102_444_800_000L,
        gelbooruUserId = "device-gel-user",
        gelbooruApiKey = "device-gel-key",
        rule34XxxUserId = "device-rule34-user",
        rule34XxxApiKey = "device-rule34-key",
    )

    private companion object {
        const val TEST_ENVELOPE_PATH = "credential_device_test/source_credentials.json"
        const val TEST_KEY_ALIAS = "theoria_source_credentials_device_test_v1"
    }
}

private fun JSONObject.keySetCompat(): Set<String> = buildSet {
    val iterator = keys()
    while (iterator.hasNext()) add(iterator.next())
}
