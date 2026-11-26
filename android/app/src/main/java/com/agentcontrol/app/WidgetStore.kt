package com.agentcontrol.app

import android.content.Context

/** Remembers which agent each home-screen widget is for. */
object WidgetStore {
    private const val PREFS = "widgets"

    data class Choice(val agentId: String, val label: String)

    fun save(context: Context, widgetId: Int, choice: Choice) {
        prefs(context).edit()
            .putString("agent_$widgetId", choice.agentId)
            .putString("label_$widgetId", choice.label)
            .apply()
    }

    fun load(context: Context, widgetId: Int): Choice? {
        val p = prefs(context)
        val id = p.getString("agent_$widgetId", null) ?: return null
        return Choice(id, p.getString("label_$widgetId", id) ?: id)
    }

    fun delete(context: Context, widgetId: Int) {
        prefs(context).edit().remove("agent_$widgetId").remove("label_$widgetId").apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
