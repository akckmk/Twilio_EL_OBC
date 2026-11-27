package com.agentcontrol.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Home-screen button: one tap ends the chosen agent's call (or all calls). */
class EndCallWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        for (id in widgetIds) draw(context, manager, id, busy = false)
    }

    override fun onDeleted(context: Context, widgetIds: IntArray) {
        for (id in widgetIds) WidgetStore.delete(context, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_END) return
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val choice = WidgetStore.load(context, widgetId)
        val settings = SecureStore.load(context)
        if (choice == null || settings == null) {
            toast(context, context.getString(R.string.widget_not_set_up))
            return
        }
        val manager = AppWidgetManager.getInstance(context)
        draw(context, manager, widgetId, busy = true)
        val pending = goAsync() // lets the network call finish after onReceive returns
        scope.launch {
            try {
                val result = CallControl(settings).end(choice.agentId)
                toast(context, result.message)
            } finally {
                draw(context, manager, widgetId, busy = false)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_END = "com.agentcontrol.app.END_CALL"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun draw(context: Context, manager: AppWidgetManager, widgetId: Int, busy: Boolean) {
            val choice = WidgetStore.load(context, widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_end_call)
            val label = when {
                choice == null -> context.getString(R.string.widget_tap_to_set_up)
                busy -> context.getString(R.string.ending)
                else -> context.getString(R.string.widget_label, choice.label)
            }
            views.setTextViewText(R.id.widget_button, label)
            if (choice != null) {
                val intent = Intent(context, EndCallWidget::class.java)
                    .setAction(ACTION_END)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                val pi = PendingIntent.getBroadcast(
                    context, widgetId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_button, pi)
            }
            manager.updateAppWidget(widgetId, views)
        }

        private fun toast(context: Context, message: String) {
            val app = context.applicationContext
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
        }
    }
}
