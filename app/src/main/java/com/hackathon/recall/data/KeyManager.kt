package com.hackathon.recall.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Key handling (docs/DECISIONS.md D-002). The SQLCipher key is 256 random bits wrapped by an AES-GCM
 * key in the Android Keystore (StrongBox when available). Vault files use a Tink streaming-AEAD
 * keyset that Tink itself encrypts with a Keystore master key. Neither key is auth-bound, because
 * background indexing and reminders must open the vault without the user; BiometricPrompt gates the UI.
 */
class KeyManager(private val context: Context) {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** SQLCipher raw-key passphrase: `x'<64 hex chars>'` (skips PBKDF2 at open). */
    fun databasePassphrase(): ByteArray {
        val file = File(context.noBackupFilesDir, "db_key.wrapped")
        val raw = if (file.exists()) {
            unwrap(file.readBytes())
        } else {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(wrap(key))
            check(tmp.renameTo(file)) { "could not store the wrapped database key" }
            key
        }
        val hex = StringBuilder(64)
        for (b in raw) hex.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        raw.fill(0)
        return "x'$hex'".toByteArray(Charsets.US_ASCII)
    }

    fun streamingAead(): StreamingAead {
        StreamingAeadConfig.register()
        val handle = AndroidKeysetManager.Builder()
            .withSharedPref(context, "vault_keyset", "recall_vault_keyset")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
            .withMasterKeyUri("android-keystore://recall_vault_master_v1")
            .build()
            .keysetHandle
        return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    /** True when the DB wrapping key lives in StrongBox (shown on the Benchmark screen). */
    var strongBoxBacked: Boolean = false
        private set

    private fun wrappingKey(): SecretKey {
        (keyStore.getKey(DB_WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(DB_WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(spec)
                generateKey()
            }
        }
        return try {
            generate(strongBox = true).also { strongBoxBacked = true }
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DB_WRAP_ALIAS = "recall_db_wrap_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val HEX = "0123456789abcdef".toCharArray()
    }
}
