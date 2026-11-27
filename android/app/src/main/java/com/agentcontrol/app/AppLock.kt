package com.agentcontrol.app

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Optional fingerprint lock. When on, the app asks for your fingerprint (or phone PIN)
 * every time it comes back to the screen. The home-screen widgets still work with one tap.
 */
object AppLock {
    @Volatile var unlocked = false
        private set

    fun install() {
        // Lock again whenever the whole app goes to the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { unlocked = false }
        })
    }

    val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun prompt(activity: AppCompatActivity, title: String, onSuccess: () -> Unit, onFail: () -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true; onSuccess()
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) = onFail()
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(activity.getString(R.string.lock_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }
}

/** Every screen extends this: hardened window + fingerprint lock. */
open class SecureActivity : AppCompatActivity() {
    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hardenWindow()
    }

    override fun onResume() {
        super.onResume()
        val content = findViewById<View>(android.R.id.content)
        if (Prefs.fingerprintLock(this) && !AppLock.unlocked) {
            content.visibility = View.INVISIBLE // nothing private shows until unlocked
            if (!prompting) {
                prompting = true
                AppLock.prompt(this, getString(R.string.app_name),
                    onSuccess = { prompting = false; content.visibility = View.VISIBLE; onUnlocked() },
                    onFail = { prompting = false; finishAffinity() })
            }
        } else {
            content.visibility = View.VISIBLE
            onUnlocked()
        }
    }

    /** Called on every resume once the app is unlocked (or the lock is off). */
    open fun onUnlocked() {}
}
