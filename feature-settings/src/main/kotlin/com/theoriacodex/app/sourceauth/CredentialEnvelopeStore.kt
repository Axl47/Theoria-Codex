package com.theoriacodex.app.sourceauth

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.AtomicFile
import android.util.Base64
import com.theoriacodex.data.repository.readBoundedDocument
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.storedVersion
import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import com.theoriacodex.domain.source.CustomSourceIds
import com.theoriacodex.domain.source.SiteCredentials
import java.io.File
import java.io.IOException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

class AndroidCredentialEnvelopeStore(
    context: Context,
    private val relativeEnvelopePath: String = CREDENTIAL_ENVELOPE_RELATIVE_PATH,
    keyAlias: String = CREDENTIAL_KEY_ALIAS,
    private val allowAtomicRecovery: Boolean = true,
) : CredentialEnvelopeStore {
    private val appContext = context.applicationContext
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

    override suspend fun read(): CredentialEnvelopeReadResult = runCatchingExceptionsPreservingCancellation {
        readSnapshot()
    }.getOrElse { error ->
        error.toCredentialReadFailure()
    }

    private suspend fun readSnapshot(): CredentialEnvelopeReadResult {
        val raw = readEnvelopeBytes() ?: return CredentialEnvelopeReadResult.Missing
        val envelopeTree = raw.parseCredential()
        newerCredentialVersion(
            envelopeTree.storedVersion("formatVersion") to CREDENTIAL_FORMAT_VERSION,
            envelopeTree.storedVersion("keyVersion") to CREDENTIAL_KEY_VERSION,
        )?.let { return CredentialEnvelopeReadResult.UnsupportedVersion(it) }
        val envelope = envelopeTree.decodeCredential<CredentialEnvelopeRecord>()
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
        val payloadTree = plaintext.parseCredential()
        newerCredentialVersion(payloadTree.storedVersion("schemaVersion") to CREDENTIAL_PAYLOAD_SCHEMA_VERSION)
            ?.let { return CredentialEnvelopeReadResult.UnsupportedVersion(it) }
        val payload = payloadTree.decodeCredential<CredentialPayloadRecord>()
        if (payload.schemaVersion <= 0) {
            return CredentialEnvelopeReadResult.ReconnectRequired
        }
        if (payload.schemaVersion != CREDENTIAL_PAYLOAD_SCHEMA_VERSION) {
            return CredentialEnvelopeReadResult.UnsupportedVersion(payload.schemaVersion)
        }
        return CredentialEnvelopeReadResult.Success(payload.toSnapshot())
    }

    private suspend fun readEnvelopeBytes(): ByteArray? {
        if (!allowAtomicRecovery && (File(envelopeFile.path + ".bak").exists() ||
                File(envelopeFile.path + ".new").exists())) {
            throw IOException("Credential envelope has an unfinished atomic write")
        }
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
        return runCatchingExceptionsPreservingCancellation {
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
        }.getOrElse { error ->
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
        val removedKey = crypto.deleteKey()
        return removedEnvelope && removedKey
    }
}

@Serializable
data class CredentialEnvelopeRecord(
    @SerialName("formatVersion")
    val formatVersion: Int = 0,
    @SerialName("keyVersion")
    val keyVersion: Int = 0,
    @SerialName("iv")
    val iv: String? = null,
    @SerialName("ciphertext")
    val ciphertext: String? = null,
)

@Serializable
data class CredentialPayloadRecord(
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
        customSites = customSites.orEmpty().mapNotNull { (sourceId, record) ->
            val user = record?.user?.takeIf(String::isNotBlank)
            val apiKey = record?.apiKey?.takeIf(String::isNotBlank)
            if (CustomSourceIds.isValid(sourceId) && user != null && apiKey != null) sourceId to SiteCredentials(user, apiKey) else null
        }.toMap(),
    )

    override fun toString(): String = "CredentialPayloadRecord([REDACTED])"
}

@Serializable
data class CustomSiteCredentialRecord(
    @SerialName("user")
    val user: String? = null,
    @SerialName("apiKey")
    val apiKey: String? = null,
) {
    override fun toString(): String = "CustomSiteCredentialRecord([REDACTED])"
}

private fun Throwable.toCredentialReadFailure(): CredentialEnvelopeReadResult {
    val causes = generateSequence(this) { error -> error.cause }
    val unrecoverable = causes.any { error ->
        error is AEADBadTagException ||
            error is KeyPermanentlyInvalidatedException ||
            error is UnrecoverableKeyException ||
            error is InvalidKeyException ||
            error is CredentialEnvelopeCorruptException
    }
    return if (unrecoverable) CredentialEnvelopeReadResult.ReconnectRequired else CredentialEnvelopeReadResult.TemporarilyUnavailable
}

private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

private fun String.decodeCredentialBase64(): ByteArray = try {
    Base64.decode(this, Base64.NO_WRAP)
} catch (error: IllegalArgumentException) {
    throw CredentialEnvelopeCorruptException(error)
}

private fun ByteArray.parseCredential(): JsonElement = try {
    DurableJson.parseToJsonElement(toString(Charsets.UTF_8))
} catch (_: SerializationException) {
    throw CredentialEnvelopeCorruptException()
}

private inline fun <reified T> JsonElement.decodeCredential(): T = try {
    DurableJson.decodeFromJsonElement<T>(this)
} catch (_: SerializationException) {
    throw CredentialEnvelopeCorruptException()
}

private fun newerCredentialVersion(vararg versions: Pair<Int?, Int>): Int? {
    for ((stored, supported) in versions) {
        if (stored != null && stored > supported) return stored
        if (stored != supported) return null
    }
    return null
}

private class CredentialEnvelopeCorruptException(cause: Throwable? = null) : Exception(cause)

private const val CREDENTIAL_FORMAT_VERSION = 1
private const val CREDENTIAL_KEY_VERSION = 1
private const val CREDENTIAL_PAYLOAD_SCHEMA_VERSION = 1
const val CREDENTIAL_KEY_ALIAS = "theoria_source_credentials_aes_gcm_v1"
internal const val CREDENTIAL_ENVELOPE_RELATIVE_PATH =
    "theoria_codex/source_credentials_v1.json"
private const val MAX_CREDENTIAL_ENVELOPE_BYTES = 64 * 1024
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BYTES = 16
