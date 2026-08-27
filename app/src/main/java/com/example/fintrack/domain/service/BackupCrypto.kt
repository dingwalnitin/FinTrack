package com.example.fintrack.domain.service

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stage 11 P23 #2 — encrypted export envelope.
 *
 * Pure-JVM AES-256-GCM with PBKDF2-HMAC-SHA256 key derivation so the whole
 * flow is unit-testable. The Android side may substitute a Keystore-backed
 * [KeyDeriver] later without touching this code (the interface is the seam).
 *
 * Envelope format:
 *   FTBACKUP1ENC1
 *   K|saltB64|iterations|ivB64
 *   <base64 ciphertext>
 *
 * Failure handling: wrong password / corrupted payload throws
 * [BackupCryptoException] with a user-safe message; no partial plaintext is
 * ever returned.
 */
object BackupCrypto {

    const val HEADER = "FTBACKUP1ENC1"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    /** Seam for a Keystore-backed deriver on Android. */
    fun interface KeyDeriver {
        fun derive(password: CharArray, salt: ByteArray): ByteArray
    }

    val DEFAULT_DERIVER = KeyDeriver { password, salt ->
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    class BackupCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun encrypt(plaintext: String, password: CharArray, deriver: KeyDeriver = DEFAULT_DERIVER): String {
        if (password.isEmpty()) throw BackupCryptoException("password must not be empty")
        val salt = ByteArray(SALT_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val key = SecretKeySpec(deriver.derive(password, salt), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = try {
            cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupCryptoException("encryption failed", e)
        }
        return buildString {
            append(HEADER).append('\n')
            append("K|").append(b64(salt)).append('|').append(ITERATIONS).append('|').append(b64(iv))
            append('\n')
            append(b64(ct))
        }
    }

    fun encrypt(plaintext: String, password: String, deriver: KeyDeriver = DEFAULT_DERIVER): String =
        encrypt(plaintext, password.toCharArray(), deriver)

    fun decrypt(envelope: String, password: CharArray, deriver: KeyDeriver = DEFAULT_DERIVER): String {
        val lines = envelope.lines()
        if (lines.firstOrNull()?.trim() != HEADER) {
            throw BackupCryptoException("not an encrypted FinTrack backup")
        }
        val kLine = lines.getOrNull(1)
            ?: throw BackupCryptoException("corrupt encrypted backup (missing key params)")
        val parts = kLine.removePrefix("K|").split('|')
        if (parts.size != 3) throw BackupCryptoException("corrupt encrypted backup (bad key params)")
        val salt = try { unb64(parts[0]) } catch (e: Exception) {
            throw BackupCryptoException("corrupt encrypted backup (salt)", e)
        }
        val iterations = parts[1].toIntOrNull()
            ?: throw BackupCryptoException("corrupt encrypted backup (iterations)")
        val iv = try { unb64(parts[2]) } catch (e: Exception) {
            throw BackupCryptoException("corrupt encrypted backup (iv)", e)
        }
        val ciphertextB64 = lines.drop(2).joinToString("").trim()
        val ct = try { unb64(ciphertextB64) } catch (e: Exception) {
            throw BackupCryptoException("corrupt encrypted backup (payload)", e)
        }
        val key = SecretKeySpec(deriver.derive(password, salt), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        // AEAD tag failure surfaces here as AEADBadTagException → wrong
        // password or tampered payload; we never return partial plaintext.
        val plaintext = try {
            cipher.doFinal(ct)
        } catch (e: Exception) {
            throw BackupCryptoException(
                "decryption failed — wrong password or the file was modified",
                e,
            )
        }
        return String(plaintext, Charsets.UTF_8)
    }

    fun decrypt(envelope: String, password: String, deriver: KeyDeriver = DEFAULT_DERIVER): String =
        decrypt(envelope, password.toCharArray(), deriver)

    fun isEncryptedEnvelope(payload: String): Boolean =
        payload.lines().firstOrNull()?.trim() == HEADER

    private fun b64(b: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(b)

    private fun unb64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}
