package com.agentcontrol.app

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shown when you add the widget: pick one agent, or "All". */
class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hardenWindow()
        // If you back out, the widget is not added.
        setResult(RESULT_CANCELED)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || !isOurWidget(widgetId)) { finish(); return }

        setContentView(R.layout.activity_widget_config)
        findViewById<android.view.View>(R.id.root).padForSystemBars()
        val status = findViewById<TextView>(R.id.status)
        val list = findViewById<LinearLayout>(R.id.choices)

        val settings = SecureStore.load(this)
        if (settings == null) {
            status.text = getString(R.string.widget_open_app_first)
            addChoice(list, getString(R.string.open_settings)) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }

        status.text = getString(R.string.loading_agents)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { Result.success(Api(settings).agents()) } catch (e: Exception) { Result.failure(e) }
            }
            result.onSuccess { agents ->
                status.text = getString(R.string.widget_pick)
                addChoice(list, getString(R.string.all_agents)) {
                    pick(WidgetStore.Choice("all", getString(R.string.all_agents_short)))
                }
                for (a in agents) addChoice(list, a.name) { pick(WidgetStore.Choice(a.id, a.name)) }
            }.onFailure {
                status.text = getString(R.string.widget_load_failed, it.message)
            }
        }
    }

    /** Another app could open this screen with any widget number; only accept our own widgets. */
    private fun isOurWidget(id: Int): Boolean {
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider ?: return false
        return provider.packageName == packageName && provider.className == EndCallWidget::class.java.name
    }

    private fun addChoice(list: LinearLayout, text: String, onClick: () -> Unit) {
        val b = MaterialButton(this).apply {
            this.text = text
            textSize = 18f
            minHeight = (64 * resources.displayMetrics.density).toInt()
            filterTouchesWhenObscured = true
            setOnClickListener { onClick() }
        }
        list.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun pick(choice: WidgetStore.Choice) {
        WidgetStore.save(this, widgetId, choice)
        EndCallWidget.draw(this, AppWidgetManager.getInstance(this), widgetId, busy = false)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
