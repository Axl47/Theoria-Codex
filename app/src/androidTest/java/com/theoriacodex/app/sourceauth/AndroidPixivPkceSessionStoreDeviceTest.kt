package com.theoriacodex.app.sourceauth

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPixivPkceSessionStoreDeviceTest {
    private lateinit var context: Context
    private lateinit var store: AndroidPixivPkceSessionStore

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
    fun constructionDoesNotCreateSessionMaterial() {
        val constructed = newStore()

        assertFalse(envelopeFile().exists())
        assertFalse(keyStore().containsAlias(TEST_KEY_ALIAS))
        assertEquals(AndroidPixivPkceSessionStore::class, constructed::class)
    }

    @Test
    fun verifiedSessionSurvivesStoreRecreationEncryptedInNoBackupStorage() = runBlocking {
        val session = session()

        store.writeVerified(session)

        val file = envelopeFile()
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        val storedBytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(storedBytes.contains(session.state))
        assertFalse(storedBytes.contains(session.codeVerifier))
        assertEquals(session, newStore().read())
        assertTrue(keyStore().containsAlias(TEST_KEY_ALIAS))
    }

    @Test
    fun consumeIsExactOneShotAndRemovesAtomicArtifacts() = runBlocking {
        val session = session()
        store.writeVerified(session)

        assertFalse(store.consume(session.copy(state = "different-state")))
        assertEquals(session, store.read())
        assertTrue(store.consume(session))
        assertEquals(null, store.read())
        assertFalse(store.consume(session))
        assertFalse(envelopeFile().exists())
        assertFalse(File(envelopeFile().path + ".bak").exists())
        assertFalse(File(envelopeFile().path + ".new").exists())
    }

    @Test
    fun tamperingFailsClosedWithoutDeletingEvidence() = runBlocking {
        store.writeVerified(session())
        val file = envelopeFile()
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        val failure = runCatching { newStore().read() }.exceptionOrNull()

        assertTrue(failure is PixivPkceSessionStoreException)
        assertTrue("tampered evidence is preserved until an explicit reset", file.exists())
    }

    private fun newStore(): AndroidPixivPkceSessionStore = AndroidPixivPkceSessionStore(
        context = context,
        relativeEnvelopePath = TEST_ENVELOPE_PATH,
        keyAlias = TEST_KEY_ALIAS,
    )

    private fun envelopeFile(): File = File(context.noBackupFilesDir, TEST_ENVELOPE_PATH)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun session(): PixivPkceSession = PixivPkceSession(
        state = "device-state-value",
        codeVerifier = "v".repeat(64),
        createdAtEpochMs = 1_000_000L,
    )

    private companion object {
        const val TEST_ENVELOPE_PATH = "pkce_device_test/pixiv_session.bin"
        const val TEST_KEY_ALIAS = "theoria_pixiv_pkce_device_test_v1"
    }
}
