package com.example.fintrack.security

import android.content.Context
import com.example.fintrack.domain.service.SecretVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stage 11 P24 #2 — Android Keystore-backed secret vault.
 *
 * The lock secret is wrapped with a Keystore AES-GCM key that is
 * non-exportable: the raw PIN bytes are never persisted anywhere (Room,
 * SharedPreferences, exports). Only the Keystore-wrapped ciphertext is kept
 * in app-private storage. If the Keystore key is invalidated (e.g. screen
 * lock removed), verification fails closed and the user must re-enable the
 * lock — never fails open.
 */
class KeystoreSecretVault(context: Context) : SecretVault {

    private val prefs = context.getSharedPreferences("fintrack_secure", Context.MODE_PRIVATE)
    private val keystoreAlias = "fintrack_lock_wrap_key"

    override val hasLockSecret: Boolean
        get() = prefs.contains(KEY_WRAPPED)

    override suspend fun storeLockSecret(secret: ByteArray) {
        require(secret.isNotEmpty()) { "lock secret must not be empty" }
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val wrapped = cipher.doFinal(secret)
        prefs.edit()
            .putString(KEY_WRAPPED, encode(wrapped))
            .putString(KEY_IV, encode(iv))
            .apply()
    }

    override suspend fun verifyLockSecret(candidate: ByteArray): Boolean {
        val wrapped = prefs.getString(KEY_WRAPPED, null)?.let { decode(it) }
            ?: return false
        val iv = prefs.getString(KEY_IV, null)?.let { decode(it) }
            ?: return false
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val unwrapped = cipher.doFinal(wrapped)
            constantTimeEquals(unwrapped, candidate)
        } catch (e: Exception) {
            // Keystore key invalidated or corrupted → fail closed.
            false
        }
    }

    override suspend fun clearLockSecret() {
        prefs.edit().remove(KEY_WRAPPED).remove(KEY_IV).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keystoreAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                keystoreAlias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(b: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(b)

    private fun decode(s: String): ByteArray =
        java.util.Base64.getDecoder().decode(s)

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    companion object {
        private const val KEY_WRAPPED = "lock_secret_wrapped"
        private const val KEY_IV = "lock_secret_iv"
    }
}
