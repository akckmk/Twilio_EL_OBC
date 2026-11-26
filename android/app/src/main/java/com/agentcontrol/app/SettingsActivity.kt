package com.agentcontrol.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where you type the backend URL and access key. Saved encrypted. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<android.view.View>(R.id.root).padForSystemBars()

        val urlField = findViewById<EditText>(R.id.url)
        val keyField = findViewById<EditText>(R.id.key)
        val status = findViewById<TextView>(R.id.status)
        val save = findViewById<Button>(R.id.save)

        SecureStore.load(this)?.let {
            urlField.setText(it.baseUrl)
            keyField.setText(it.accessKey)
        }

        save.setOnClickListener {
            val url = Backend.normalizeUrl(urlField.text.toString())
            val key = keyField.text.toString().trim()
            if (!url.startsWith("https://") || url.length < 12) {
                status.text = getString(R.string.settings_bad_url); return@setOnClickListener
            }
            if (key.length < 32) {
                status.text = getString(R.string.settings_short_key); return@setOnClickListener
            }
            val settings = SecureStore.Settings(url, key)
            save.isEnabled = false
            status.text = getString(R.string.settings_testing)
            lifecycleScope.launch {
                // Test the connection before saving, so typos show up right away.
                val error = withContext(Dispatchers.IO) {
                    try { Backend(settings).agents(); null } catch (e: Exception) { e.message }
                }
                save.isEnabled = true
                if (error != null) {
                    status.text = getString(R.string.settings_failed, error)
                } else {
                    SecureStore.save(this@SettingsActivity, settings)
                    startActivity(Intent(this@SettingsActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                }
            }
        }
    }
}
