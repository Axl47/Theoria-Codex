package com.theoriacodex.app.sourceauth

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.AtomicFile
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import kotlin.coroutines.cancellation.CancellationException

internal class AndroidCredentialEnvelopeStore(
    context: Context,
    private val gson: Gson = Gson(),
    private val relativeEnvelopePath: String = CREDENTIAL_ENVELOPE_RELATIVE_PATH,
    keyAlias: String = CREDENTIAL_KEY_ALIAS,
) : CredentialEnvelopeStore {
    private val appContext = context.applicationContext
    // Context.noBackupFilesDir may create directories. Defer it until a suspending operation so
    // constructing the application graph performs no storage I/O.
    private val envelopeFile: File by lazy {
        File(appContext.noBackupFilesDir, relativeEnvelopePath)
    }
    private val crypto = AndroidKeystoreAesGcm(
        alias = keyAlias,
        additionalAuthenticatedData =
            "${appContext.packageName}|source-credentials|format-$CREDENTIAL_FORMAT_VERSION"
                .toByteArray(Charsets.UTF_8),
    )

    override suspend fun read(): CredentialEnvelopeReadResult {
        return try {
            val atomicFile = AtomicFile(envelopeFile)
            // An interrupted legacy AtomicFile write may leave only the committed .bak file.
            if (!envelopeFile.hasCommittedAtomicData()) {
                return CredentialEnvelopeReadResult.Missing
            }
            val raw = atomicFile.readBounded(MAX_CREDENTIAL_ENVELOPE_BYTES) {
                CredentialEnvelopeCorruptException(
                    IllegalArgumentException("Credential envelope exceeds its bounded storage limit"),
                )
            }
            val envelope = gson.fromJson(
                raw.toString(Charsets.UTF_8),
                CredentialEnvelopeRecord::class.java,
            ) ?: return CredentialEnvelopeReadResult.ReconnectRequired
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
            val payload = gson.fromJson(
                plaintext.toString(Charsets.UTF_8),
                CredentialPayloadRecord::class.java,
            ) ?: return CredentialEnvelopeReadResult.ReconnectRequired
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

    override suspend fun writeVerified(
        snapshot: SourceCredentialSnapshot,
    ): CredentialStoreWriteResult {
        return try {
            val plaintext = gson.toJson(CredentialPayloadRecord(snapshot)).toByteArray(Charsets.UTF_8)
            val encrypted = crypto.encrypt(plaintext)
            val envelope = CredentialEnvelopeRecord(
                formatVersion = CREDENTIAL_FORMAT_VERSION,
                keyVersion = CREDENTIAL_KEY_VERSION,
                iv = encrypted.iv.encodeBase64(),
                ciphertext = encrypted.ciphertext.encodeBase64(),
            )
            val encoded = gson.toJson(envelope).toByteArray(Charsets.UTF_8)
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

internal class AndroidLegacyCredentialStore(
    context: Context,
) : LegacyCredentialStore {
    private val appContext = context.applicationContext
    private val legacyFile = File(
        appContext.applicationInfo.dataDir,
        "shared_prefs/$PREFS_NAME.xml",
    )
    private val legacyBackupFile = File(legacyFile.path + ".bak")

    override suspend fun read(): LegacyCredentialReadResult {
        return try {
            if (!legacyFile.exists() && !legacyBackupFile.exists()) {
                return if (legacyMasterKeyExists()) {
                    // An orphaned alias means a prior cleanup/reset was interrupted. An empty
                    // verified migration gives the state machine a safe point to finish it.
                    LegacyCredentialReadResult.Success(SourceCredentialSnapshot())
                } else {
                    LegacyCredentialReadResult.Missing
                }
            }
            val preferences = createEncryptedPrefs(appContext)
            val snapshot = SourceCredentialSnapshot(
                pixivAccessToken = preferences.getString(KEY_PIXIV_ACCESS_TOKEN, null),
                pixivRefreshToken = preferences.getString(KEY_PIXIV_REFRESH_TOKEN, null),
                pixivExpiresAtEpochMs = preferences.getLong(KEY_PIXIV_EXPIRES_AT, -1L)
                    .takeIf { value -> value > 0L },
                gelbooruUserId = preferences.getString(KEY_GELBOORU_USER_ID, null),
                gelbooruApiKey = preferences.getString(KEY_GELBOORU_API_KEY, null),
                rule34XxxUserId = preferences.getString(KEY_RULE34XXX_USER_ID, null),
                rule34XxxApiKey = preferences.getString(KEY_RULE34XXX_API_KEY, null),
            )
            // Existing-but-empty preferences must still retire their file and deprecated alias.
            LegacyCredentialReadResult.Success(snapshot)
        } catch (error: Exception) {
            if (error.isRecoverableEncryptedPrefsFailure()) {
                LegacyCredentialReadResult.ReconnectRequired
            } else {
                LegacyCredentialReadResult.TemporarilyUnavailable
            }
        }
    }

    override suspend fun cleanup(): Boolean {
        val preferencesRemoved = removeLegacyPreferences()
        // Never short-circuit. File and alias deletion can independently fail, and both are
        // idempotent so an interrupted cleanup remains recoverable.
        val keyRemoved = deleteLegacyMasterKey()
        return preferencesRemoved && keyRemoved
    }

    override suspend fun reset(): Boolean {
        val preferencesRemoved = removeLegacyPreferences()
        val keyRemoved = deleteLegacyMasterKey()
        return preferencesRemoved && keyRemoved
    }

    private fun removeLegacyPreferences(): Boolean = runCatching {
        if (legacyFile.exists() || legacyBackupFile.exists()) {
            appContext.deleteSharedPreferences(PREFS_NAME)
        }
        !legacyFile.exists() && !legacyBackupFile.exists()
    }.getOrDefault(false)

    @Suppress("DEPRECATION") // Migration-only lookup for the retired Security Crypto alias.
    private fun legacyMasterKeyExists(): Boolean {
        return KeyStore.getInstance("AndroidKeyStore").run {
            load(null)
            containsAlias(androidx.security.crypto.MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
    }
}

private class CredentialEnvelopeRecord(
    @field:SerializedName("formatVersion")
    val formatVersion: Int = 0,
    @field:SerializedName("keyVersion")
    val keyVersion: Int = 0,
    @field:SerializedName("iv")
    val iv: String? = null,
    @field:SerializedName("ciphertext")
    val ciphertext: String? = null,
)

private class CredentialPayloadRecord(
    @field:SerializedName("schemaVersion")
    val schemaVersion: Int = CREDENTIAL_PAYLOAD_SCHEMA_VERSION,
    @field:SerializedName("pixivAccessToken")
    val pixivAccessToken: String? = null,
    @field:SerializedName("pixivRefreshToken")
    val pixivRefreshToken: String? = null,
    @field:SerializedName("pixivExpiresAtEpochMs")
    val pixivExpiresAtEpochMs: Long? = null,
    @field:SerializedName("gelbooruUserId")
    val gelbooruUserId: String? = null,
    @field:SerializedName("gelbooruApiKey")
    val gelbooruApiKey: String? = null,
    @field:SerializedName("rule34XxxUserId")
    val rule34XxxUserId: String? = null,
    @field:SerializedName("rule34XxxApiKey")
    val rule34XxxApiKey: String? = null,
) {
    constructor(snapshot: SourceCredentialSnapshot) : this(
        pixivAccessToken = snapshot.pixivAccessToken,
        pixivRefreshToken = snapshot.pixivRefreshToken,
        pixivExpiresAtEpochMs = snapshot.pixivExpiresAtEpochMs,
        gelbooruUserId = snapshot.gelbooruUserId,
        gelbooruApiKey = snapshot.gelbooruApiKey,
        rule34XxxUserId = snapshot.rule34XxxUserId,
        rule34XxxApiKey = snapshot.rule34XxxApiKey,
    )

    fun toSnapshot(): SourceCredentialSnapshot = SourceCredentialSnapshot(
        pixivAccessToken = pixivAccessToken,
        pixivRefreshToken = pixivRefreshToken,
        pixivExpiresAtEpochMs = pixivExpiresAtEpochMs,
        gelbooruUserId = gelbooruUserId,
        gelbooruApiKey = gelbooruApiKey,
        rule34XxxUserId = rule34XxxUserId,
        rule34XxxApiKey = rule34XxxApiKey,
    )

    override fun toString(): String = "CredentialPayloadRecord([REDACTED])"
}

private fun Exception.toCredentialReadFailure(): CredentialEnvelopeReadResult {
    val causes = generateSequence(this as Throwable?) { error -> error.cause }.toList()
    return when {
        causes.any { error ->
            error is AEADBadTagException ||
                error is KeyPermanentlyInvalidatedException ||
                error is UnrecoverableKeyException ||
                error is InvalidKeyException ||
                error is JsonParseException ||
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

private class CredentialEnvelopeCorruptException(cause: Throwable) : Exception(cause)

private const val CREDENTIAL_FORMAT_VERSION = 1
private const val CREDENTIAL_KEY_VERSION = 1
private const val CREDENTIAL_PAYLOAD_SCHEMA_VERSION = 1
internal const val CREDENTIAL_KEY_ALIAS = "theoria_source_credentials_aes_gcm_v1"
internal const val CREDENTIAL_ENVELOPE_RELATIVE_PATH =
    "theoria_codex/source_credentials_v1.json"
private const val MAX_CREDENTIAL_ENVELOPE_BYTES = 64 * 1024
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BYTES = 16
