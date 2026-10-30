package com.example.walletlookup

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected backups: AES-256-GCM with a key derived from the password (PBKDF2, 600,000 rounds,
 * random salt). Without the password the file can't be read; a wrong password is detected, never "half" opened.
 * The file says how many rounds it used, so older backups (200,000 rounds) still open.
 */
object BackupCrypto {
    /** Rounds for new backups. */
    const val ROUNDS = 600_000
    /** Rounds of backups that don't say (all made before the number was saved in the file used this). */
    const val LEGACY_ROUNDS = 200_000
    private const val MIN_ROUNDS = 100_000
    private const val MAX_ROUNDS = 2_000_000

    /**
     * The rounds to open a file with, from its "rounds" field ([stored], 0 when missing): kept between 100,000 and
     * 2,000,000, so a damaged or tampered file can't make opening it take forever (or be weakened).
     */
    fun roundsFor(stored: Int): Int = if (stored <= 0) LEGACY_ROUNDS else stored.coerceIn(MIN_ROUNDS, MAX_ROUNDS)

    private fun key(password: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, rounds, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    /** The encryption itself, on plain bytes (no Android classes, so it can be checked by the unit tests). */
    internal fun sealBytes(plain: ByteArray, password: String, salt: ByteArray, iv: ByteArray, rounds: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password.toCharArray(), salt, rounds), GCMParameterSpec(128, iv))
        return cipher.doFinal(plain)
    }

    /** Opposite of [sealBytes]; throws when the password (or rounds) is wrong or the data was changed. */
    internal fun openBytes(data: ByteArray, password: String, salt: ByteArray, iv: ByteArray, rounds: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password.toCharArray(), salt, rounds), GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }

    fun encrypt(plain: String, password: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val data = sealBytes(plain.toByteArray(Charsets.UTF_8), password, salt, iv, ROUNDS)
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        return JSONObject().put("app", "Batwallet").put("encrypted", 1).put("kdf", "PBKDF2-SHA256").put("rounds", ROUNDS)
            .put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(data)).toString(2)
    }

    fun isEncrypted(text: String): Boolean =
        runCatching { JSONObject(text.trim()).optInt("encrypted") == 1 }.getOrDefault(false)

    /** Throws when the password is wrong or the file was changed. */
    fun decrypt(text: String, password: String): String {
        val o = JSONObject(text.trim())
        fun b(k: String) = Base64.decode(o.getString(k), Base64.NO_WRAP)
        val rounds = roundsFor(o.optInt("rounds", 0))
        return String(openBytes(b("data"), password, b("salt"), b("iv"), rounds), Charsets.UTF_8)
    }
}
