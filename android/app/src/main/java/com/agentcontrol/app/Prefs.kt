package com.agentcontrol.app

import android.content.Context

/** Simple on/off settings (not secret). */
object Prefs {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun notifications(c: Context) = p(c).getBoolean("notifications", false)
    fun setNotifications(c: Context, on: Boolean) = p(c).edit().putBoolean("notifications", on).apply()

    fun fingerprintLock(c: Context) = p(c).getBoolean("fingerprint_lock", false)
    fun setFingerprintLock(c: Context, on: Boolean) = p(c).edit().putBoolean("fingerprint_lock", on).apply()
}
