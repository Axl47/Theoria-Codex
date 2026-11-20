package com.theoriacodex.app.developercopy

import com.theoriacodex.app.sourceauth.SourceCredentialSnapshot
import com.theoriacodex.data.repository.PROFILE_BACKUP_MAX_BYTES
import com.theoriacodex.data.repository.ProfileBackupCodec
import com.theoriacodex.data.repository.checkDocumentDepth
import com.theoriacodex.data.repository.decodeDocumentUtf8
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.domain.model.SourceKey
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

internal const val PRODUCTION_PACKAGE = "com.theoriacodex"
internal const val DEBUG_PACKAGE = "com.theoriacodex.debug"
internal const val COPY_AUTHORITY = "$PRODUCTION_PACKAGE.developer-copy"
internal const val COPY_DIRECTORY = "developer_copy"
internal const val COPY_TIMEOUT_MS = 120_000L
private const val COPY_MAGIC = 0x54434431 // TCD1
private const val MAX_CREDENTIAL_BYTES = 64 * 1024

/** Secrets exist only in the authenticated pipe and each app's memory/Keystore storage. */
internal class DeveloperCopyPayload(val backup: ByteArray, val credentials: SourceCredentialSnapshot) {
    override fun toString(): String = "DeveloperCopyPayload([REDACTED])"
}

internal fun writeCopyPayload(output: OutputStream, payload: DeveloperCopyPayload) {
    val credentialBytes = encodeCredentials(payload.credentials)
    require(payload.backup.size in 1..PROFILE_BACKUP_MAX_BYTES)
    require(credentialBytes.size in 1..MAX_CREDENTIAL_BYTES)
    DataOutputStream(output).apply {
        writeInt(COPY_MAGIC)
        writeInt(credentialBytes.size)
        write(credentialBytes)
        writeInt(payload.backup.size)
        write(payload.backup)
        flush()
    }
}

internal fun readCopyPayload(input: InputStream): DeveloperCopyPayload {
    val stream = DataInputStream(input)
    require(stream.readInt() == COPY_MAGIC)
    val credentials = decodeCredentials(readSection(stream, MAX_CREDENTIAL_BYTES))
    val backup = readSection(stream, PROFILE_BACKUP_MAX_BYTES)
    require(stream.read() == -1) { "Unexpected trailing payload" }
    ProfileBackupCodec().decode(backup)
    return DeveloperCopyPayload(backup, credentials)
}

private fun readSection(input: DataInputStream, maximum: Int): ByteArray {
    val length = input.readInt()
    require(length in 1..maximum)
    return ByteArray(length).also(input::readFully)
}

private fun encodeCredentials(snapshot: SourceCredentialSnapshot): ByteArray = DurableJson.encodeToString(
    DeveloperCopyCredentials.serializer(),
    DeveloperCopyCredentials(
        pixivAccessToken = snapshot.pixivAccessToken,
        pixivRefreshToken = snapshot.pixivRefreshToken,
        pixivExpiresAtEpochMs = snapshot.pixivExpiresAtEpochMs,
        gelbooruUserId = snapshot.gelbooruUserId,
        gelbooruApiKey = snapshot.gelbooruApiKey,
        rule34XxxUserId = snapshot.rule34XxxUserId,
        rule34XxxApiKey = snapshot.rule34XxxApiKey,
    ),
).toByteArray(Charsets.UTF_8)

/** Failures carry no message or cause from the parser, whose errors quote the input and so the secrets. */
private fun decodeCredentials(bytes: ByteArray): SourceCredentialSnapshot {
    val raw = decodeDocumentUtf8(bytes)
    checkDocumentDepth(raw)
    val record = try {
        DurableJson.decodeFromString<DeveloperCopyCredentials>(raw)
    } catch (_: SerializationException) {
        throw IllegalArgumentException("Unreadable developer copy credentials")
    }
    fun String?.checked(): String? = this?.also { value -> require(value.isNotBlank() && value.length <= MAX_CREDENTIAL_BYTES) }
    val snapshot = SourceCredentialSnapshot(
        pixivAccessToken = record.pixivAccessToken.checked(),
        pixivRefreshToken = record.pixivRefreshToken.checked(),
        pixivExpiresAtEpochMs = record.pixivExpiresAtEpochMs,
        gelbooruUserId = record.gelbooruUserId.checked(),
        gelbooruApiKey = record.gelbooruApiKey.checked(),
        rule34XxxUserId = record.rule34XxxUserId.checked(),
        rule34XxxApiKey = record.rule34XxxApiKey.checked(),
    )
    require(snapshot.pixivAccessToken == null && snapshot.pixivRefreshToken == null &&
        snapshot.pixivExpiresAtEpochMs == null || snapshot.pixivTokens() != null)
    require(snapshot.gelbooruUserId == null && snapshot.gelbooruApiKey == null || snapshot.userApiKey(SourceKey.GELBOORU) != null)
    require(snapshot.rule34XxxUserId == null && snapshot.rule34XxxApiKey == null || snapshot.userApiKey(SourceKey.RULE34XXX) != null)
    return snapshot
}

/** The credential section of the copy pipe. Older builds wrote absent values as JSON null; this one omits them. */
@Serializable
private class DeveloperCopyCredentials(
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
)

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).toHexString()
