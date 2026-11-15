package de.doorzign.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.doorzign.app.core.TokenStore
import de.doorzign.app.core.Tokens
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

/**
 * Tokens verschlüsselt (AES-256-GCM) mit einem nicht exportierbaren Schlüssel aus dem Android Keystore.
 * Gespeichert wird nur der Chiffretext; `commit()` stellt sicher, dass ein rotiertes Paar auf der Platte liegt,
 * bevor es benutzt wird.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("dz_tokens", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cached: Tokens? = null
    @Volatile private var loaded = false

    @Synchronized
    override fun load(): Tokens? {
        if (loaded) return cached
        cached = prefs.getString(KEY_BLOB, null)?.let { blob ->
            runCatching { json.decodeFromString<Tokens>(decrypt(blob)) }.getOrElse {
                // Schlüssel ungültig (z. B. Gerät zurückgesetzt) → wie abgemeldet behandeln
                prefs.edit().remove(KEY_BLOB).commit()
                null
            }
        }
        loaded = true
        return cached
    }

    @Synchronized
    override fun save(tokens: Tokens) {
        prefs.edit().putString(KEY_BLOB, encrypt(json.encodeToString(Tokens.serializer(), tokens))).commit()
        cached = tokens
        loaded = true
    }

    @Synchronized
    override fun clear() {
        prefs.edit().clear().commit()
        cached = null
        loaded = true
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
        return String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "doorzign_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val KEY_BLOB = "blob"
    }
}
