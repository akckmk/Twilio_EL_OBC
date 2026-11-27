package com.agentcontrol.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Optional (Settings > Call notifications). Checks /live every 30 s, or every 3 s while a
 * call is live, and notifies when a call starts or ends. Android requires a small
 * "Watching for calls" notification while this runs.
 */
class CallWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.WATCH_ID, Notifier.watching(this), type)
        if (loop?.isActive != true) loop = scope.launch { watch() }
        return START_STICKY
    }

    private suspend fun watch() {
        var known: Map<String, Api.LiveCall>? = null
        while (scope.isActive) {
            val settings = SecureStore.load(this)
            if (settings == null || !Prefs.notifications(this)) { stopSelf(); return }
            val live = try { Api(settings).live() } catch (e: Exception) { null }
            if (live != null) {
                val now = live.calls.associateBy { it.callSid }
                if (known != null) {
                    for ((sid, c) in now) if (sid !in known) {
                        Notifier.callEvent(this, sid.hashCode(), getString(R.string.notify_started),
                            "${c.agentName} → ${Contacts.label(this, c.number)}")
                    }
                    for ((sid, c) in known) if (sid !in now) {
                        val talked = c.answeredAt?.let { Format.duration((live.serverTime - it).toInt()) }
                        Notifier.callEvent(this, sid.hashCode(), getString(R.string.notify_ended),
                            "${c.agentName} → ${Contacts.label(this, c.number)}" + (talked?.let { " · about $it" } ?: ""))
                    }
                }
                known = now
            }
            delay(if (known.isNullOrEmpty()) 30_000 else 3_000)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun sync(c: Context) {
            val intent = Intent(c, CallWatchService::class.java)
            if (Prefs.notifications(c) && SecureStore.load(c) != null) {
                try { ContextCompat.startForegroundService(c, intent) } catch (e: Exception) { /* not allowed right now */ }
            } else {
                c.stopService(intent)
            }
        }
    }
}
