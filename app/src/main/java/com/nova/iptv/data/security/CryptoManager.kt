package com.nova.iptv.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Envelope encryption:
 *  - A non-exportable AES-256 key lives in the Android Keystore.
 *  - It wraps a random data key (DEK) stored in private prefs.
 *  - The DEK (kept only in memory once unwrapped) encrypts secrets with AES-GCM.
 * This keeps bulk encryption of thousands of M3U URLs fast while the root key
 * never leaves secure hardware.
 */
@Singleton
class CryptoManager @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("nova_keys", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    @Volatile
    private var dek: SecretKey? = null

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    private fun dataKey(): SecretKey {
        dek?.let { return it }
        val wrapped = prefs.getString(PREF_DEK, null)
        val key: SecretKey = if (wrapped != null) {
            val bytes = Base64.decode(wrapped, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
            SecretKeySpec(cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)), "AES")
        } else {
            val raw = ByteArray(32).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            val out = cipher.iv + cipher.doFinal(raw)
            prefs.edit().putString(PREF_DEK, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
            SecretKeySpec(raw, "AES")
        }
        dek = key
        return key
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, dataKey(), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /**
     * Fast encryptor for bulk work on a single thread (e.g. 50k playlist URLs):
     * reuses one Cipher instance instead of looking one up per value.
     */
    fun bulkEncryptor(): (String) -> String {
        val key = dataKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_SIZE)
        return { plain ->
            if (plain.isEmpty()) "" else {
                random.nextBytes(iv)
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            }
        }
    }

    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, dataKey(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
            String(cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "nova_root_key"
        private const val PREF_DEK = "dek"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}

/** Passphrase-based encryption for exported settings files (portable between devices). */
object BackupCrypto {
    private const val MAGIC = "NOVA1"
    private const val ITERATIONS = 120_000

    private fun derive(passphrase: CharArray, salt: ByteArray, algo: String): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance(algo).generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun pbkdfAlgorithm(): String = try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256"); "PBKDF2WithHmacSHA256"
    } catch (e: Exception) {
        "PBKDF2WithHmacSHA1"
    }

    fun encrypt(plain: String, passphrase: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val algo = pbkdfAlgorithm()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(passphrase.toCharArray(), salt, algo), GCMParameterSpec(128, iv))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val header = "$MAGIC|$algo|${b64(salt)}|${b64(iv)}|".toByteArray(Charsets.UTF_8)
        return header + b64(body).toByteArray(Charsets.UTF_8)
    }

    /** @throws IllegalArgumentException on wrong format or passphrase. */
    fun decrypt(data: ByteArray, passphrase: String): String {
        val parts = String(data, Charsets.UTF_8).split("|")
        require(parts.size == 5 && parts[0] == MAGIC) { "format" }
        val algo = parts[1]
        val salt = unb64(parts[2])
        val iv = unb64(parts[3])
        val body = unb64(parts[4])
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(passphrase.toCharArray(), salt, algo), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalArgumentException("passphrase")
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
