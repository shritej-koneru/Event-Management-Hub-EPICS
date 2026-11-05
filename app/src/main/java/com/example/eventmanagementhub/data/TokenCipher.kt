package com.example.eventmanagementhub.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts/decrypts the session token. [decrypt] throws on any failure. */
interface TokenCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
}

/** AES-256/GCM with a non-exportable Android Keystore key. Output: Base64(iv || ciphertext). */
class KeystoreTokenCipher : TokenCipher {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key()) // Keystore generates a random IV
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        require(raw.size > IV_LEN) { "ciphertext too short" }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
        return String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "emh_session_token_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}

/**
 * Pure storage logic over a raw string slot (JVM-testable with a fake cipher).
 * Stored values are `v1:<cipher output>`; a non-blank value without that prefix is a
 * legacy plaintext JWT, which is re-saved encrypted once on first read.
 */
class TokenVault(
    private val cipher: TokenCipher,
    private val read: suspend () -> String?,
    private val write: suspend (String) -> Unit
) {
    suspend fun load(): String? {
        val stored = read()?.takeIf { it.isNotBlank() } ?: return null
        if (!stored.startsWith(PREFIX)) {
            // Legacy plaintext: migrate. If encryption fails, drop it and require re-login.
            return try {
                write(PREFIX + cipher.encrypt(stored)); stored
            } catch (e: Exception) {
                write(""); null
            }
        }
        return try {
            cipher.decrypt(stored.removePrefix(PREFIX)).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            write("") // key lost / corrupt: clear, user logs in again
            null
        }
    }

    suspend fun save(token: String) = write(PREFIX + cipher.encrypt(token))

    suspend fun clear() = write("")

    companion object {
        const val PREFIX = "v1:"
    }
}
