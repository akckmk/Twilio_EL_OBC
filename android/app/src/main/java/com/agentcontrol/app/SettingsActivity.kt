package com.agentcontrol.app

import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where you paste your Twilio and ElevenLabs keys. Saved encrypted. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<android.view.View>(R.id.root).padForSystemBars()
        findViewById<TextView>(R.id.help_twilio).movementMethod = LinkMovementMethod.getInstance()
        findViewById<TextView>(R.id.help_elevenlabs).movementMethod = LinkMovementMethod.getInstance()

        val accountSid = findViewById<EditText>(R.id.account_sid)
        val keySid = findViewById<EditText>(R.id.key_sid)
        val keySecret = findViewById<EditText>(R.id.key_secret)
        val elKey = findViewById<EditText>(R.id.el_key)
        val status = findViewById<TextView>(R.id.status)
        val save = findViewById<Button>(R.id.save)

        SecureStore.load(this)?.let {
            accountSid.setText(it.accountSid)
            keySid.setText(it.apiKeySid)
            keySecret.setText(it.apiKeySecret)
            elKey.setText(it.elevenLabsKey)
        }

        save.setOnClickListener {
            // Remove spaces and line breaks that sneak in when pasting.
            fun clean(e: EditText) = e.text.toString().replace(Regex("\\s"), "")
            val settings = SecureStore.Settings(clean(accountSid), clean(keySid), clean(keySecret), clean(elKey))

            val problem = when {
                !Regex("^AC[0-9a-fA-F]{32}$").matches(settings.accountSid) ->
                    getString(R.string.bad_account_sid)
                !Regex("^SK[0-9a-fA-F]{32}$").matches(settings.apiKeySid) ->
                    getString(R.string.bad_key_sid)
                settings.apiKeySecret.length < 20 -> getString(R.string.bad_key_secret)
                settings.elevenLabsKey.length < 20 -> getString(R.string.bad_el_key)
                else -> null
            }
            if (problem != null) { status.text = problem; return@setOnClickListener }

            save.isEnabled = false
            status.text = getString(R.string.settings_testing)
            lifecycleScope.launch {
                // Try both keys before saving, so a typo shows up right away.
                val error = withContext(Dispatchers.IO) {
                    try {
                        val control = CallControl(settings)
                        control.checkTwilio()
                        control.agents()
                        null
                    } catch (e: CallControl.ApiException) {
                        e.message
                    } catch (e: Exception) {
                        getString(R.string.no_internet)
                    }
                }
                save.isEnabled = true
                if (error != null) {
                    status.text = getString(R.string.settings_failed, error)
                } else {
                    SecureStore.save(this@SettingsActivity, settings)
                    startActivity(
                        Intent(this@SettingsActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                    finish()
                }
            }
        }
    }
}
