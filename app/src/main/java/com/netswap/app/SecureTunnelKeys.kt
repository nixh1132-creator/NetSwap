package com.netswap.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local WireGuard key storage.
 * The private key is encrypted with an Android Keystore AES key and is never sent to Supabase.
 */
object SecureTunnelKeys {
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val AES_ALIAS = "netswap_tunnel_key_encryption_v1"
    private const val PREFS = "netswap_tunnel_keys"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Synchronized
    fun getOrCreate(context: Context, requestId: String): Pair<String, String> {
        require(requestId.matches(Regex("^[0-9a-fA-F-]{36}$"))) { "Invalid sharing request." }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(requestId, null)
        val privateKeyBase64 = if (stored != null) {
            decrypt(stored)
        } else {
            val generated = KeyPair()
            val privateValue = generated.privateKey.toBase64()
            val encrypted = encrypt(privateValue)
            check(prefs.edit().putString(requestId, encrypted).commit()) { "Could not save this phone's private key." }
            privateValue
        }
        val pair = KeyPair(Key.fromBase64(privateKeyBase64))
        return Pair(pair.publicKey.toBase64(), privateKeyBase64)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = store.getKey(AES_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        require(combined.size > 12) { "Stored tunnel key is invalid." }
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }
}
