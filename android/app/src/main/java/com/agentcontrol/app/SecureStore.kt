package com.agentcontrol.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps your Twilio and ElevenLabs keys encrypted on the phone.
 * The encryption key lives in the Android Keystore (the phone's secure hardware)
 * and never leaves it, so the saved text is useless if copied off the phone.
 */
object SecureStore {
    private const val KEY_ALIAS = "agent_control_settings_key_v2"
    private const val PREFS = "secure_settings"
    private const val FIELD = "blob_v2"

    data class Settings(
        val accountSid: String,
        val apiKeySid: String,
        val apiKeySecret: String,
        val elevenLabsKey: String,
    )

    fun save(context: Context, settings: Settings) {
        val plain = JSONObject()
            .put("accountSid", settings.accountSid)
            .put("apiKeySid", settings.apiKeySid)
            .put("apiKeySecret", settings.apiKeySecret)
            .put("elevenLabsKey", settings.elevenLabsKey)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(plain)
        prefs(context).edit().putString(FIELD, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun load(context: Context): Settings? {
        val stored = prefs(context).getString(FIELD, null) ?: return null
        return try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val iv = sealed.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(sealed.copyOfRange(12, sealed.size)), Charsets.UTF_8))
            Settings(
                json.getString("accountSid"),
                json.getString("apiKeySid"),
                json.getString("apiKeySecret"),
                json.getString("elevenLabsKey"),
            )
        } catch (e: Exception) {
            null // Unreadable (for example after a phone reset): ask for the settings again.
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
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
