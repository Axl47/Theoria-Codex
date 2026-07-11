package com.theoriacodex.app.sourceauth

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidPixivPkceSessionStore(
    context: Context,
    private val relativeEnvelopePath: String = PIXIV_PKCE_ENVELOPE_RELATIVE_PATH,
    keyAlias: String = PIXIV_PKCE_KEY_ALIAS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PixivPkceSessionStore {
    private val appContext = context.applicationContext
    private val envelopeFile: File by lazy {
        File(appContext.noBackupFilesDir, relativeEnvelopePath)
    }
    private val crypto = AndroidKeystoreAesGcm(
        alias = keyAlias,
        additionalAuthenticatedData =
            "${appContext.packageName}|pixiv-pkce-session|format-$PIXIV_PKCE_ENVELOPE_VERSION"
                .toByteArray(Charsets.UTF_8),
    )

    override suspend fun read(): PixivPkceSession? = withContext(ioDispatcher) {
        pixivPkceStoreMutex.withLock { readCommittedSession() }
    }

    override suspend fun writeVerified(session: PixivPkceSession) = withContext(ioDispatcher) {
        pixivPkceStoreMutex.withLock {
            validatePixivPkceSession(session)
            try {
                val plaintext = encodeSession(session)
                val encrypted = crypto.encrypt(plaintext)
                val envelope = encodeEnvelope(encrypted)
                require(envelope.size <= MAX_PIXIV_PKCE_ENVELOPE_BYTES) {
                    "Pixiv authorization session envelope exceeds its bounded limit"
                }
                val parent = envelopeFile.parentFile
                check(parent == null || parent.exists() || parent.mkdirs() || parent.exists()) {
                    "Pixiv authorization session directory is unavailable"
                }
                AtomicFile(envelopeFile).writeFully(envelope)
                check(readCommittedSession() == session) {
                    "Pixiv authorization session verification failed"
                }
            } catch (error: PixivPkceSessionStoreException) {
                throw error
            } catch (error: Exception) {
                throw PixivPkceSessionStoreException(error)
            }
        }
    }

    override suspend fun consume(session: PixivPkceSession): Boolean = withContext(ioDispatcher) {
        pixivPkceStoreMutex.withLock {
            if (readCommittedSession() != session) return@withLock false
            try {
                AtomicFile(envelopeFile).delete()
                !envelopeFile.hasAtomicArtifacts()
            } catch (error: Exception) {
                throw PixivPkceSessionStoreException(error)
            }
        }
    }

    override suspend fun reset(): Boolean = withContext(ioDispatcher) {
        pixivPkceStoreMutex.withLock {
            val removedEnvelope = runCatching {
                AtomicFile(envelopeFile).delete()
                !envelopeFile.hasAtomicArtifacts()
            }.getOrDefault(false)
            val removedKey = crypto.deleteKey()
            removedEnvelope && removedKey
        }
    }

    private fun readCommittedSession(): PixivPkceSession? {
        if (!envelopeFile.hasCommittedAtomicData()) return null
        return try {
            val encrypted = decodeEnvelope(
                AtomicFile(envelopeFile).readBounded(
                    maxBytes = MAX_PIXIV_PKCE_ENVELOPE_BYTES,
                    bufferSize = 1_024,
                ) {
                    IllegalArgumentException(
                        "Pixiv authorization session envelope exceeds its bounded limit",
                    )
                },
            )
            decodeSession(crypto.decrypt(encrypted.iv, encrypted.ciphertext))
        } catch (error: PixivPkceSessionStoreException) {
            throw error
        } catch (error: Exception) {
            throw PixivPkceSessionStoreException(error)
        }
    }
}

internal class PixivPkceSessionStoreException(cause: Throwable) : IllegalStateException(
    "Pixiv authorization session storage is unavailable",
    cause,
)

private fun encodeSession(session: PixivPkceSession): ByteArray {
    return ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(PIXIV_PKCE_PAYLOAD_MAGIC)
            output.writeInt(PIXIV_PKCE_PAYLOAD_VERSION)
            output.writeLong(session.createdAtEpochMs)
            output.writeSizedUtf8(session.state)
            output.writeSizedUtf8(session.codeVerifier)
        }
        bytes.toByteArray()
    }
}

private fun decodeSession(bytes: ByteArray): PixivPkceSession {
    require(bytes.size <= MAX_PIXIV_PKCE_PAYLOAD_BYTES) {
        "Pixiv authorization session payload exceeds its bounded limit"
    }
    return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == PIXIV_PKCE_PAYLOAD_MAGIC) {
            "Pixiv authorization session payload is invalid"
        }
        require(input.readInt() == PIXIV_PKCE_PAYLOAD_VERSION) {
            "Pixiv authorization session payload version is unsupported"
        }
        val session = PixivPkceSession(
            createdAtEpochMs = input.readLong(),
            state = input.readSizedUtf8(),
            codeVerifier = input.readSizedUtf8(),
        )
        require(input.available() == 0) { "Pixiv authorization session payload has trailing data" }
        validatePixivPkceSession(session)
        session
    }
}

private fun encodeEnvelope(encrypted: EncryptedAesGcmBytes): ByteArray {
    require(encrypted.iv.size == PIXIV_PKCE_GCM_IV_BYTES) {
        "Pixiv authorization session IV is invalid"
    }
    require(encrypted.ciphertext.size >= PIXIV_PKCE_GCM_TAG_BYTES) {
        "Pixiv authorization session ciphertext is invalid"
    }
    return ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(PIXIV_PKCE_ENVELOPE_MAGIC)
            output.writeInt(PIXIV_PKCE_ENVELOPE_VERSION)
            output.writeInt(PIXIV_PKCE_KEY_VERSION)
            output.writeInt(encrypted.iv.size)
            output.write(encrypted.iv)
            output.writeInt(encrypted.ciphertext.size)
            output.write(encrypted.ciphertext)
        }
        bytes.toByteArray()
    }
}

private fun decodeEnvelope(bytes: ByteArray): EncryptedAesGcmBytes {
    return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == PIXIV_PKCE_ENVELOPE_MAGIC) {
            "Pixiv authorization session envelope is invalid"
        }
        require(input.readInt() == PIXIV_PKCE_ENVELOPE_VERSION) {
            "Pixiv authorization session envelope version is unsupported"
        }
        require(input.readInt() == PIXIV_PKCE_KEY_VERSION) {
            "Pixiv authorization session key version is unsupported"
        }
        val ivLength = input.readInt()
        require(ivLength == PIXIV_PKCE_GCM_IV_BYTES) {
            "Pixiv authorization session IV is invalid"
        }
        val iv = ByteArray(ivLength)
        input.readFully(iv)
        val ciphertextLength = input.readInt()
        require(ciphertextLength in PIXIV_PKCE_GCM_TAG_BYTES..MAX_PIXIV_PKCE_ENVELOPE_BYTES) {
            "Pixiv authorization session ciphertext is invalid"
        }
        val ciphertext = ByteArray(ciphertextLength)
        input.readFully(ciphertext)
        require(input.available() == 0) { "Pixiv authorization session envelope has trailing data" }
        EncryptedAesGcmBytes(iv = iv, ciphertext = ciphertext)
    }
}

private fun DataOutputStream.writeSizedUtf8(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_PIXIV_PKCE_STRING_BYTES) {
        "Pixiv authorization session value exceeds its bounded limit"
    }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readSizedUtf8(): String {
    val length = readInt()
    require(length in 1..MAX_PIXIV_PKCE_STRING_BYTES) {
        "Pixiv authorization session value length is invalid"
    }
    val bytes = ByteArray(length)
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
}

internal const val PIXIV_PKCE_KEY_ALIAS = "theoria_pixiv_pkce_session_aes_gcm_v1"
internal const val PIXIV_PKCE_ENVELOPE_RELATIVE_PATH =
    "theoria_codex/pixiv_pkce_session_v1.bin"
private const val PIXIV_PKCE_ENVELOPE_MAGIC = 0x54504B45
private const val PIXIV_PKCE_PAYLOAD_MAGIC = 0x54504B50
private const val PIXIV_PKCE_ENVELOPE_VERSION = 1
private const val PIXIV_PKCE_KEY_VERSION = 1
private const val PIXIV_PKCE_PAYLOAD_VERSION = 1
private const val MAX_PIXIV_PKCE_ENVELOPE_BYTES = 4 * 1_024
private const val MAX_PIXIV_PKCE_PAYLOAD_BYTES = 1 * 1_024
private const val MAX_PIXIV_PKCE_STRING_BYTES = 256
private const val PIXIV_PKCE_GCM_IV_BYTES = 12
private const val PIXIV_PKCE_GCM_TAG_BYTES = 16

private val pixivPkceStoreMutex = Mutex()
