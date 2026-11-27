package com.agentcontrol.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** "Call started" / "Call ended" notifications. Private details are hidden on the lock screen. */
object Notifier {
    const val CALLS = "calls"
    const val WATCH = "watch"
    const val WATCH_ID = 1

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CALLS, c.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(WATCH, c.getString(R.string.channel_watch), NotificationManager.IMPORTANCE_MIN))
    }

    private fun openApp(c: Context) = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, R.id.nav_live)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun watching(c: Context): Notification = NotificationCompat.Builder(c, WATCH)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle(c.getString(R.string.watching_title))
        .setContentText(c.getString(R.string.watching_text))
        .setContentIntent(openApp(c))
        .setOngoing(true)
        .build()

    fun callEvent(c: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val public = NotificationCompat.Builder(c, CALLS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title.substringBefore(":"))
            .build()
        val n = NotificationCompat.Builder(c, CALLS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(c))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public) // lock screen shows only "Call started" / "Call ended"
            .build()
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (e: SecurityException) { }
    }
}
