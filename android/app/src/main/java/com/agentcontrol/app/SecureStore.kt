package com.agentcontrol.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the backend URL and access key - and private data like contacts and the
 * cached call history - encrypted on the phone. The encryption key lives in the
 * Android Keystore (StrongBox / Knox Vault when available) and never leaves it.
 *
 * The app NEVER stores Twilio or ElevenLabs keys. Those live only in the backend.
 */
object SecureStore {
    // DO NOT RENAME these three. Updates keep your saved settings only because a new
    // version finds them under the same names. Changing any of them = re-enter settings.
    private const val KEY_ALIAS = "agent_control_settings_key_v2"
    private const val PREFS = "secure_settings"
    private const val FIELD = "blob_v2"

    data class Settings(val baseUrl: String, val accessKey: String)

    fun save(context: Context, settings: Settings) {
        val json = JSONObject().put("baseUrl", settings.baseUrl).put("accessKey", settings.accessKey)
        prefs(context).edit().putString(FIELD, seal(json.toString().toByteArray())).apply()
    }

    fun load(context: Context): Settings? {
        val stored = prefs(context).getString(FIELD, null) ?: return null
        return try {
            val json = JSONObject(String(open(stored), Charsets.UTF_8))
            if (!json.has("baseUrl")) {
                // Saved by the older version that held Twilio/ElevenLabs keys on the phone.
                // Wipe them: the app must never hold those keys any more.
                prefs(context).edit().remove(FIELD).apply()
                return null
            }
            Settings(json.getString("baseUrl"), json.getString("accessKey"))
        } catch (e: Exception) {
            null
        }
    }

    /** Encrypted private file (contacts, cached history). */
    fun writeFile(context: Context, name: String, text: String) {
        File(context.filesDir, "$name.enc").writeText(seal(text.toByteArray(Charsets.UTF_8)))
    }

    fun readFile(context: Context, name: String): String? = try {
        val f = File(context.filesDir, "$name.enc")
        if (f.exists()) String(open(f.readText()), Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    fun deleteFile(context: Context, name: String) {
        File(context.filesDir, "$name.enc").delete()
    }

    private fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    private fun open(stored: String): ByteArray {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return cipher.doFinal(sealed.copyOfRange(12, sealed.size))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Only tests replace this (the simulator has no security chip). Internal to the app module. */
    @androidx.annotation.VisibleForTesting
    internal var keyProvider: () -> SecretKey = ::keystoreKey

    private fun secretKey(): SecretKey = keyProvider()

    @Synchronized
    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        // Prefer the phone's separate security chip (StrongBox; "Knox Vault" on Samsung).
        try { return generate(strongBox = true) } catch (e: Exception) { /* not available; fall back */ }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
        if (strongBox) builder.setIsStrongBoxBacked(true)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(builder.build())
        return generator.generateKey()
    }
}
