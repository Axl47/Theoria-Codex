package com.theoriacodex.app.sourceauth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Shared mechanics for the independently versioned credential and one-shot PKCE envelopes. */
internal class AndroidKeystoreAesGcm(
    private val alias: String,
    private val additionalAuthenticatedData: ByteArray,
) {
    fun hasKey(): Boolean = keyStore().containsAlias(alias)

    fun encrypt(plaintext: ByteArray): EncryptedAesGcmBytes {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(additionalAuthenticatedData)
        return EncryptedAesGcmBytes(
            iv = cipher.iv.copyOf(),
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = keyStore().getKey(alias, null) as? SecretKey
            ?: throw UnrecoverableKeyException("Encrypted storage key is unavailable")
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(additionalAuthenticatedData)
        return cipher.doFinal(ciphertext)
    }

    fun deleteKey(): Boolean = runCatching {
        keyStore().apply {
            if (containsAlias(alias)) deleteEntry(alias)
        }
        true
    }.getOrDefault(false)

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore().getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}

internal data class EncryptedAesGcmBytes(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

internal fun AtomicFile.readBounded(
    maxBytes: Int,
    bufferSize: Int = 4 * 1_024,
    overflowFailure: () -> Throwable = {
        IllegalArgumentException("Encrypted envelope exceeds its bounded storage limit")
    },
): ByteArray {
    require(maxBytes > 0 && bufferSize > 0)
    return openRead().use { input ->
        val output = ByteArrayOutputStream(minOf(maxBytes, bufferSize * 2))
        val buffer = ByteArray(bufferSize)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw overflowFailure()
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
}

internal fun AtomicFile.writeFully(bytes: ByteArray) {
    val output = startWrite()
    try {
        output.write(bytes)
        output.flush()
        output.fd.sync()
        finishWrite(output)
    } catch (error: Throwable) {
        failWrite(output)
        throw error
    }
}

internal fun File.hasCommittedAtomicData(): Boolean = exists() || File(path + ".bak").exists()

internal fun File.hasAtomicArtifacts(): Boolean =
    hasCommittedAtomicData() || File(path + ".new").exists()

private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
