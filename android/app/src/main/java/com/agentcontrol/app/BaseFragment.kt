package com.agentcontrol.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A pull-to-refresh, scrolling screen whose content is rebuilt from data. */
abstract class BaseFragment : Fragment(), Refreshable {
    protected lateinit var content: LinearLayout
    protected lateinit var status: TextView
    protected lateinit var refresher: SwipeRefreshLayout

    protected val settings get() = SecureStore.load(requireContext())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val c = requireContext()
        content = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 24))
        }
        status = TextView(c).apply { setPadding(0, Ui.dp(c, 8), 0, 0) }
        val column = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; addView(header()); addView(status); addView(content) }
        refresher = SwipeRefreshLayout(c).apply {
            addView(ScrollView(c).apply { addView(column) })
            setOnRefreshListener { refresh() }
        }
        return refresher
    }

    /** Fixed controls above the content (buttons, filters). */
    open fun header(): View = View(requireContext())

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Runs [load] off the main thread, then [render] on it. Shows errors in [status]. */
    protected fun <T> load(load: (Api) -> T, render: (T) -> Unit) {
        val s = settings ?: return
        refresher.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { load(Api(s)) } }
            if (!isAdded) return@launch
            refresher.isRefreshing = false
            r.onSuccess { status.text = ""; render(it) }.onFailure { status.text = it.message }
        }
    }
}
