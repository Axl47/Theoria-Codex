package com.theoriacodex.app.sourceauth

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.AtomicFile
import android.util.Base64
import com.theoriacodex.data.repository.readBoundedDocument
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.domain.source.CustomSourceIds
import com.theoriacodex.domain.source.SiteCredentials
import java.io.File
import java.io.IOException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

internal class AndroidCredentialEnvelopeStore(
    context: Context,
    private val relativeEnvelopePath: String = CREDENTIAL_ENVELOPE_RELATIVE_PATH,
    keyAlias: String = CREDENTIAL_KEY_ALIAS,
    private val allowAtomicRecovery: Boolean = true,
) : CredentialEnvelopeStore {
    private val appContext = context.applicationContext
    // Context.noBackupFilesDir may create directories. Defer it until a suspending operation so
    // constructing the application graph performs no storage I/O.
    private val envelopeFile: File by lazy {
        val directory = if (allowAtomicRecovery) appContext.noBackupFilesDir else
            File(appContext.applicationInfo.dataDir, "no_backup")
        File(directory, relativeEnvelopePath)
    }
    private val crypto = AndroidKeystoreAesGcm(
        alias = keyAlias,
        additionalAuthenticatedData =
            "${appContext.packageName}|source-credentials|format-$CREDENTIAL_FORMAT_VERSION"
                .toByteArray(Charsets.UTF_8),
    )

    override suspend fun read(): CredentialEnvelopeReadResult {
        return try {
            val raw = readEnvelopeBytes() ?: return CredentialEnvelopeReadResult.Missing
            val envelope = CredentialEnvelopeRecord.serializer().decodeCredential(raw)
            if (envelope.formatVersion <= 0) {
                return CredentialEnvelopeReadResult.ReconnectRequired
            }
            if (envelope.formatVersion != CREDENTIAL_FORMAT_VERSION) {
                return CredentialEnvelopeReadResult.UnsupportedVersion(envelope.formatVersion)
            }
            if (envelope.keyVersion <= 0) {
                return CredentialEnvelopeReadResult.ReconnectRequired
            }
            if (envelope.keyVersion != CREDENTIAL_KEY_VERSION) {
                return CredentialEnvelopeReadResult.UnsupportedVersion(envelope.keyVersion)
            }
            if (!crypto.hasKey()) return CredentialEnvelopeReadResult.ReconnectRequired
            val iv = envelope.iv?.decodeCredentialBase64()
                ?: return CredentialEnvelopeReadResult.ReconnectRequired
            val ciphertext = envelope.ciphertext?.decodeCredentialBase64()
                ?: return CredentialEnvelopeReadResult.ReconnectRequired
            if (iv.size != GCM_IV_BYTES || ciphertext.size < GCM_TAG_BYTES) {
                return CredentialEnvelopeReadResult.ReconnectRequired
            }
            val plaintext = crypto.decrypt(
                iv = iv,
                ciphertext = ciphertext,
            )
            val payload = CredentialPayloadRecord.serializer().decodeCredential(plaintext)
            if (payload.schemaVersion <= 0) {
                return CredentialEnvelopeReadResult.ReconnectRequired
            }
            if (payload.schemaVersion != CREDENTIAL_PAYLOAD_SCHEMA_VERSION) {
                return CredentialEnvelopeReadResult.UnsupportedVersion(payload.schemaVersion)
            }
            CredentialEnvelopeReadResult.Success(payload.toSnapshot())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.toCredentialReadFailure()
        }
    }

    private suspend fun readEnvelopeBytes(): ByteArray? {
        // AtomicFile.openRead may rename .bak or remove .new. A developer export must
        // neither repair those artifacts nor create the no-backup directory while reading.
        if (!allowAtomicRecovery && (File(envelopeFile.path + ".bak").exists() ||
                File(envelopeFile.path + ".new").exists())) {
            throw IOException("Credential envelope has an unfinished atomic write")
        }
        // An interrupted ordinary read may recover only the committed .bak file.
        if (!envelopeFile.hasCommittedAtomicData()) return null
        if (!allowAtomicRecovery) return envelopeFile.inputStream().use {
            readBoundedDocument(it, MAX_CREDENTIAL_ENVELOPE_BYTES)
        }
        return AtomicFile(envelopeFile).readBounded(MAX_CREDENTIAL_ENVELOPE_BYTES) {
            CredentialEnvelopeCorruptException(
                IllegalArgumentException("Credential envelope exceeds its bounded storage limit"),
            )
        }
    }

    override suspend fun writeVerified(
        snapshot: SourceCredentialSnapshot,
    ): CredentialStoreWriteResult {
        return try {
            val plaintext = DurableJson.encodeToString(CredentialPayloadRecord.serializer(), CredentialPayloadRecord(snapshot))
                .toByteArray(Charsets.UTF_8)
            val encrypted = crypto.encrypt(plaintext)
            val envelope = CredentialEnvelopeRecord(
                formatVersion = CREDENTIAL_FORMAT_VERSION,
                keyVersion = CREDENTIAL_KEY_VERSION,
                iv = encrypted.iv.encodeBase64(),
                ciphertext = encrypted.ciphertext.encodeBase64(),
            )
            val encoded = DurableJson.encodeToString(CredentialEnvelopeRecord.serializer(), envelope).toByteArray(Charsets.UTF_8)
            if (encoded.size > MAX_CREDENTIAL_ENVELOPE_BYTES) {
                return CredentialStoreWriteResult.TemporarilyUnavailable
            }
            val parent = envelopeFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                return CredentialStoreWriteResult.TemporarilyUnavailable
            }
            AtomicFile(envelopeFile).writeFully(encoded)
            when (val verified = read()) {
                is CredentialEnvelopeReadResult.Success -> {
                    if (verified.snapshot == snapshot) {
                        CredentialStoreWriteResult.Success
                    } else {
                        CredentialStoreWriteResult.TemporarilyUnavailable
                    }
                }
                CredentialEnvelopeReadResult.ReconnectRequired ->
                    CredentialStoreWriteResult.ReconnectRequired
                else -> CredentialStoreWriteResult.TemporarilyUnavailable
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            when (error.toCredentialReadFailure()) {
                CredentialEnvelopeReadResult.ReconnectRequired ->
                    CredentialStoreWriteResult.ReconnectRequired
                else -> CredentialStoreWriteResult.TemporarilyUnavailable
            }
        }
    }

    override suspend fun reset(): Boolean {
        val removedEnvelope = runCatching {
            val atomicFile = AtomicFile(envelopeFile)
            atomicFile.delete()
            !envelopeFile.hasAtomicArtifacts()
        }.getOrDefault(false)
        // Always attempt both halves; each operation is idempotent on a later retry.
        val removedKey = crypto.deleteKey()
        return removedEnvelope && removedKey
    }
}

/** The on-disk envelope around the encrypted payload. Old Gson-written envelopes decode unchanged. */
@Serializable
internal data class CredentialEnvelopeRecord(
    @SerialName("formatVersion")
    val formatVersion: Int = 0,
    @SerialName("keyVersion")
    val keyVersion: Int = 0,
    @SerialName("iv")
    val iv: String? = null,
    @SerialName("ciphertext")
    val ciphertext: String? = null,
)

/** The decrypted payload. A data class for equality in golden tests; [toString] stays redacted. */
@Serializable
internal data class CredentialPayloadRecord(
    @SerialName("schemaVersion")
    val schemaVersion: Int = CREDENTIAL_PAYLOAD_SCHEMA_VERSION,
    @SerialName("pixivAccessToken")
    val pixivAccessToken: String? = null,
    @SerialName("pixivRefreshToken")
    val pixivRefreshToken: String? = null,
    @SerialName("pixivExpiresAtEpochMs")
    val pixivExpiresAtEpochMs: Long? = null,
    @SerialName("gelbooruUserId")
    val gelbooruUserId: String? = null,
    @SerialName("gelbooruApiKey")
    val gelbooruApiKey: String? = null,
    @SerialName("rule34XxxUserId")
    val rule34XxxUserId: String? = null,
    @SerialName("rule34XxxApiKey")
    val rule34XxxApiKey: String? = null,
    /** Additive in payload schema 1 (plan D7); absent when no custom site has keys. */
    @SerialName("customSites")
    val customSites: Map<String, CustomSiteCredentialRecord?>? = null,
) {
    constructor(snapshot: SourceCredentialSnapshot) : this(
        pixivAccessToken = snapshot.pixivAccessToken,
        pixivRefreshToken = snapshot.pixivRefreshToken,
        pixivExpiresAtEpochMs = snapshot.pixivExpiresAtEpochMs,
        gelbooruUserId = snapshot.gelbooruUserId,
        gelbooruApiKey = snapshot.gelbooruApiKey,
        rule34XxxUserId = snapshot.rule34XxxUserId,
        rule34XxxApiKey = snapshot.rule34XxxApiKey,
        customSites = snapshot.customSites.takeIf(Map<*, *>::isNotEmpty)
            ?.mapValues { (_, credentials) -> CustomSiteCredentialRecord(credentials.user, credentials.apiKey) },
    )

    fun toSnapshot(): SourceCredentialSnapshot = SourceCredentialSnapshot(
        pixivAccessToken = pixivAccessToken,
        pixivRefreshToken = pixivRefreshToken,
        pixivExpiresAtEpochMs = pixivExpiresAtEpochMs,
        gelbooruUserId = gelbooruUserId,
        gelbooruApiKey = gelbooruApiKey,
        rule34XxxUserId = rule34XxxUserId,
        rule34XxxApiKey = rule34XxxApiKey,
        // An entry that is not a valid custom id with both values is dropped, never fatal.
        customSites = customSites.orEmpty().mapNotNull { (sourceId, record) ->
            val user = record?.user?.takeIf(String::isNotBlank)
            val apiKey = record?.apiKey?.takeIf(String::isNotBlank)
            if (CustomSourceIds.isValid(sourceId) && user != null && apiKey != null) sourceId to SiteCredentials(user, apiKey) else null
        }.toMap(),
    )

    override fun toString(): String = "CredentialPayloadRecord([REDACTED])"
}

@Serializable
internal data class CustomSiteCredentialRecord(
    @SerialName("user")
    val user: String? = null,
    @SerialName("apiKey")
    val apiKey: String? = null,
) {
    override fun toString(): String = "CustomSiteCredentialRecord([REDACTED])"
}

private fun Exception.toCredentialReadFailure(): CredentialEnvelopeReadResult {
    val causes = generateSequence(this as Throwable?) { error -> error.cause }.toList()
    return when {
        causes.any { error ->
            error is AEADBadTagException ||
                error is KeyPermanentlyInvalidatedException ||
                error is UnrecoverableKeyException ||
                error is InvalidKeyException ||
                error is CredentialEnvelopeCorruptException
        } -> CredentialEnvelopeReadResult.ReconnectRequired
        causes.any { error -> error is IOException || error is java.security.KeyStoreException } ->
            CredentialEnvelopeReadResult.TemporarilyUnavailable
        else -> CredentialEnvelopeReadResult.TemporarilyUnavailable
    }
}

private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

private fun String.decodeCredentialBase64(): ByteArray = try {
    Base64.decode(this, Base64.NO_WRAP)
} catch (error: IllegalArgumentException) {
    throw CredentialEnvelopeCorruptException(error)
}

/**
 * Decodes stored credential JSON. kotlinx quotes part of its input in parse errors, and here the input can be a
 * decrypted payload, so a parse failure is replaced by a corrupt-envelope error that carries neither its message
 * nor its cause. A corrupt envelope or payload therefore asks for reconnection.
 */
private fun <T> KSerializer<T>.decodeCredential(bytes: ByteArray): T = try {
    DurableJson.decodeFromString(this, bytes.toString(Charsets.UTF_8))
} catch (_: SerializationException) {
    throw CredentialEnvelopeCorruptException()
}

/** A stored envelope that can never decode. Its message and cause stay empty when they could quote secrets. */
private class CredentialEnvelopeCorruptException(cause: Throwable? = null) : Exception(cause)

private const val CREDENTIAL_FORMAT_VERSION = 1
private const val CREDENTIAL_KEY_VERSION = 1
private const val CREDENTIAL_PAYLOAD_SCHEMA_VERSION = 1
internal const val CREDENTIAL_KEY_ALIAS = "theoria_source_credentials_aes_gcm_v1"
internal const val CREDENTIAL_ENVELOPE_RELATIVE_PATH =
    "theoria_codex/source_credentials_v1.json"
private const val MAX_CREDENTIAL_ENVELOPE_BYTES = 64 * 1024
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BYTES = 16
