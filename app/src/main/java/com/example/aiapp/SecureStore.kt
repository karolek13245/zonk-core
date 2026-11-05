package com.example.aiapp

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cleans a pasted API key: keeps only visible ASCII characters (no spaces, no line breaks, no hidden characters). */
fun cleanApiKey(raw: String): String = raw.filter { it.code in 0x21..0x7E }.take(4096)

/**
 * Replaces every copy of [secret] inside [text] with dots, so a key can't show up in an error message
 * (and from there in the chat or in the saved chat history).
 */
fun redactSecret(text: String, secret: String): String =
    if (secret.length < 6) text else text.replace(secret, "••••")

/**
 * Locks a short secret with AES-256-GCM. The result is plain text that looks like `enc1:<iv>:<scrambled>`
 * (both parts Base64). GCM also notices if the text was changed, so a damaged value is refused, not misread.
 */
internal object SecretBox {
    private const val PREFIX = "enc1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun seal(key: SecretKey, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)   // the cipher picks a fresh random IV every time
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return PREFIX + b64.encodeToString(cipher.iv) + ":" + b64.encodeToString(sealed)
    }

    /** The original text, or null if [stored] isn't ours, was changed, or [key] is the wrong key. */
    fun open(key: SecretKey, stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        val parts = stored.substring(PREFIX.length).split(':')
        if (parts.size != 2) return null
        return try {
            val b64 = Base64.getDecoder()
            val iv = b64.decode(parts[0])
            val sealed = b64.decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

/** The lock itself: a key that lives in the Android Keystore. The app can use it but can't read it out. */
internal object AndroidKeys {
    private const val ALIAS = "zonk_core_api_key_v1"

    /** The key, or null if there is none yet and [create] is false. */
    fun get(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

/**
 * Keeps the cloud API key encrypted. Only the scrambled text is written to the settings file; the key that
 * unscrambles it stays in the Android Keystore (the phone's secure storage).
 *
 * Versions up to 1.3.1 kept the key as plain text under the name "key": [loadApiKey] finds such a key, locks it
 * and deletes the plain copy. If the phone's Keystore doesn't work, the key is never written as plain text:
 * it is kept in memory only (until the app closes) and [storageOk] turns false so Settings can say so.
 */
class SecureStore(
    private val prefs: SharedPreferences,
    private val keyFor: (Boolean) -> SecretKey? = AndroidKeys::get,
) {
    private companion object {
        const val KEY_ENC = "key_enc"
        const val KEY_PLAIN = "key"
        const val KEY_FAILED = "keyStoreFailed"
    }

    /** False if the phone's Keystore let us down the last time we saved: the key is then only kept in memory. */
    fun storageOk(): Boolean = !prefs.getBoolean(KEY_FAILED, false)

    /** The saved key, or "" if there is none (or it can't be read back). */
    @Synchronized
    fun loadApiKey(): String {
        val sealed = prefs.getString(KEY_ENC, null)
        if (sealed != null) {
            val lock = try { keyFor(false) } catch (e: Exception) { null }
            return if (lock == null) "" else SecretBox.open(lock, sealed).orEmpty()
        }
        val old = prefs.getString(KEY_PLAIN, null) ?: return ""
        val clean = cleanApiKey(old)
        saveApiKey(clean)   // locks it up and removes the plain-text copy
        return clean
    }

    /** Saves [clean] (see [cleanApiKey]). Returns false if the key could not be stored securely. */
    @Synchronized
    fun saveApiKey(clean: String): Boolean {
        if (clean.isEmpty()) {
            prefs.edit().remove(KEY_ENC).remove(KEY_PLAIN).putBoolean(KEY_FAILED, false).apply()
            return true
        }
        return try {
            val lock = keyFor(true) ?: throw IllegalStateException("no key")
            val sealed = SecretBox.seal(lock, clean)
            prefs.edit().putString(KEY_ENC, sealed).remove(KEY_PLAIN).putBoolean(KEY_FAILED, false).apply()
            true
        } catch (e: Exception) {
            // Never fall back to plain text: drop every stored copy and keep the key in memory only.
            prefs.edit().remove(KEY_ENC).remove(KEY_PLAIN).putBoolean(KEY_FAILED, true).apply()
            false
        }
    }
}
