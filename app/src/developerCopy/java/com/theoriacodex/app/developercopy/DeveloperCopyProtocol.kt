package com.theoriacodex.app.developercopy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theoriacodex.app.sourceauth.SourceCredentialSnapshot
import com.theoriacodex.data.repository.PROFILE_BACKUP_MAX_BYTES
import com.theoriacodex.data.repository.ProfileBackupCodec
import com.theoriacodex.data.repository.checkDocumentDepth
import com.theoriacodex.data.repository.decodeDocumentUtf8
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

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

private fun encodeCredentials(snapshot: SourceCredentialSnapshot): ByteArray = JsonObject().apply {
    addProperty("pixivAccessToken", snapshot.pixivAccessToken)
    addProperty("pixivRefreshToken", snapshot.pixivRefreshToken)
    addProperty("pixivExpiresAtEpochMs", snapshot.pixivExpiresAtEpochMs)
    addProperty("gelbooruUserId", snapshot.gelbooruUserId)
    addProperty("gelbooruApiKey", snapshot.gelbooruApiKey)
    addProperty("rule34XxxUserId", snapshot.rule34XxxUserId)
    addProperty("rule34XxxApiKey", snapshot.rule34XxxApiKey)
}.toString().toByteArray(Charsets.UTF_8)

private fun decodeCredentials(bytes: ByteArray): SourceCredentialSnapshot {
    val raw = decodeDocumentUtf8(bytes)
    checkDocumentDepth(raw)
    val root = JsonParser.parseString(raw).asJsonObject
    fun text(key: String): String? = root.get(key)?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
        it.asString.also { value -> require(value.isNotBlank() && value.length <= MAX_CREDENTIAL_BYTES) }
    }
    val snapshot = SourceCredentialSnapshot(
        pixivAccessToken = text("pixivAccessToken"),
        pixivRefreshToken = text("pixivRefreshToken"),
        pixivExpiresAtEpochMs = root.get("pixivExpiresAtEpochMs")?.takeUnless { it.isJsonNull }?.asLong,
        gelbooruUserId = text("gelbooruUserId"),
        gelbooruApiKey = text("gelbooruApiKey"),
        rule34XxxUserId = text("rule34XxxUserId"),
        rule34XxxApiKey = text("rule34XxxApiKey"),
    )
    require(snapshot.pixivAccessToken == null && snapshot.pixivRefreshToken == null &&
        snapshot.pixivExpiresAtEpochMs == null || snapshot.pixivTokens() != null)
    require(snapshot.gelbooruUserId == null && snapshot.gelbooruApiKey == null || snapshot.gelbooruCredentials() != null)
    require(snapshot.rule34XxxUserId == null && snapshot.rule34XxxApiKey == null || snapshot.rule34XxxCredentials() != null)
    return snapshot
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
