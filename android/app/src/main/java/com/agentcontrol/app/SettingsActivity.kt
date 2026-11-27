package com.agentcontrol.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backend URL + access key (saved encrypted), notifications, fingerprint lock, setup check. */
class SettingsActivity : SecureActivity() {
    private lateinit var notifications: MaterialSwitch

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Prefs.setNotifications(this, granted)
        notifications.isChecked = granted
        if (!granted) Toast.makeText(this, R.string.notify_permission_needed, Toast.LENGTH_LONG).show()
        CallWatchService.sync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<android.view.View>(R.id.root).padForSystemBars()

        val url = findViewById<EditText>(R.id.url)
        val key = findViewById<EditText>(R.id.key)
        val status = findViewById<TextView>(R.id.status)
        val save = findViewById<Button>(R.id.save)
        notifications = findViewById(R.id.notifications)
        val fingerprint = findViewById<MaterialSwitch>(R.id.fingerprint)

        // The access key is never shown again after saving; leave it empty to keep it.
        val saved = SecureStore.load(this)
        if (saved != null) {
            url.setText(saved.baseUrl)
            findViewById<TextInputLayout>(R.id.key_layout).helperText = getString(R.string.saved_hidden)
        }

        save.setOnClickListener {
            val base = Api.normalizeUrl(url.text.toString())
            val accessKey = key.text.toString().trim().ifEmpty { saved?.accessKey.orEmpty() }
            if (!base.startsWith("https://") || base.length < 12) { status.text = getString(R.string.settings_bad_url); return@setOnClickListener }
            if (accessKey.length < 32) { status.text = getString(R.string.settings_short_key); return@setOnClickListener }
            val settings = SecureStore.Settings(base, accessKey)
            save.isEnabled = false
            status.text = getString(R.string.settings_testing)
            lifecycleScope.launch {
                val error = withContext(Dispatchers.IO) { runCatching { Api(settings).agents() }.exceptionOrNull()?.message }
                save.isEnabled = true
                if (error != null) { status.text = getString(R.string.settings_failed, error); return@launch }
                SecureStore.save(this@SettingsActivity, settings)
                clearClipboard()
                startActivity(Intent(this@SettingsActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }

        notifications.isChecked = Prefs.notifications(this)
        notifications.setOnCheckedChangeListener { _, on ->
            if (on && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Prefs.setNotifications(this, on)
                CallWatchService.sync(this)
            }
        }

        fingerprint.isChecked = Prefs.fingerprintLock(this)
        fingerprint.setOnCheckedChangeListener { sw, on ->
            if (!on) { Prefs.setFingerprintLock(this, false); return@setOnCheckedChangeListener }
            if (BiometricManager.from(this).canAuthenticate(AppLock.authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
                sw.isChecked = false
                Toast.makeText(this, R.string.no_fingerprint, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            // Prove it works before turning it on, so you can't lock yourself out.
            AppLock.prompt(this, getString(R.string.setting_fingerprint),
                onSuccess = { Prefs.setFingerprintLock(this, true) },
                onFail = { sw.isChecked = false })
        }

        val healthResult = findViewById<TextView>(R.id.health_result)
        findViewById<Button>(R.id.health).setOnClickListener {
            val s = SecureStore.load(this) ?: run { healthResult.text = getString(R.string.save_first); return@setOnClickListener }
            healthResult.text = getString(R.string.loading)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Api(s).health() } }
                healthResult.text = r.fold({ h ->
                    val checks = h.optJSONArray("checks")
                    buildString {
                        append(h.optString("message")).append("\n")
                        for (i in 0 until (checks?.length() ?: 0)) checks!!.getJSONObject(i).let {
                            append(if (it.optBoolean("ok")) "✅ " else "❌ ").append(it.optString("name"))
                            if (!it.optBoolean("ok")) append(" — needs ").append(it.optString("needs"))
                            append("\n")
                        }
                    }
                }, { it.message })
            }
        }
        findViewById<Button>(R.id.setup).setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        findViewById<Button>(R.id.contacts).setOnClickListener { startActivity(Intent(this, ContactsActivity::class.java)) }
    }

    /** The key was probably pasted, so wipe it from the clipboard. */
    private fun clearClipboard() {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
        try { cm.clearPrimaryClip() } catch (e: Exception) { }
    }
}
