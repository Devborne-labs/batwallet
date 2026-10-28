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
 * Password-protected backups: AES-256-GCM with a key derived from the password (PBKDF2, 200,000 rounds,
 * random salt). Without the password the file can't be read; a wrong password is detected, never "half" opened.
 */
object BackupCrypto {
    private const val ROUNDS = 200_000

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ROUNDS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: String, password: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password.toCharArray(), salt), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
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
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password.toCharArray(), b("salt")), GCMParameterSpec(128, b("iv")))
        return String(cipher.doFinal(b("data")), Charsets.UTF_8)
    }
}