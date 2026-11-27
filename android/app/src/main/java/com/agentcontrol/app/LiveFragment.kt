package com.agentcontrol.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.agentcontrol.app.Ui.redButton
import com.agentcontrol.app.Ui.small
import com.agentcontrol.app.Ui.text
import com.agentcontrol.app.Ui.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live calls. Refreshes every 3 s while a call is live, every 30 s when none is,
 * and ticks the timers every second in between.
 */
class LiveFragment : BaseFragment() {
    private var live: Api.Live? = null
    private var fetchedAtMs = 0L        // phone clock when the data arrived
    private var serverOffset = 0.0      // server clock minus phone clock (seconds)
    private val timerViews = mutableMapOf<String, TextView>()
    private lateinit var updated: TextView
    private var lastError: String? = null

    override fun header(): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), 0)
        redButton(getString(R.string.end_all)) { end("all", null, it) }.setBackgroundColor(context.getColor(R.color.end_red_dark))
        updated = small(getString(R.string.loading))
    }

    override fun onResume() {
        super.onResume() // triggers one refresh()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { // poller
                    while (true) {
                        delay(if (live?.calls.isNullOrEmpty()) 30_000 else 3_000)
                        fetch()
                    }
                }
                while (true) { tick(); delay(1000) } // timers + "last updated"
            }
        }
    }

    override fun refresh() { viewLifecycleOwner.lifecycleScope.launch { fetch() } }

    private suspend fun fetch() {
        val s = settings ?: return
        val r = withContext(Dispatchers.IO) { runCatching { Api(s).live() } }
        if (!isAdded) return
        refresher.isRefreshing = false
        r.onSuccess {
            lastError = null
            live = it
            fetchedAtMs = System.currentTimeMillis()
            serverOffset = it.serverTime - fetchedAtMs / 1000.0
            render(it)
        }.onFailure {
            lastError = it.message
            // Show problems in plain view, not just in the small "last updated" line.
            status.text = getString(R.string.live_error, it.message ?: "")
        }
        tick()
    }

    private fun render(data: Api.Live) {
        content.removeAllViews(); timerViews.clear()
        status.text = if (data.calls.isEmpty()) getString(R.string.no_live_calls) else ""
        for (c in data.calls) {
            content.addView(Ui.card(requireContext()) {
                title(c.agentName)
                val who = Contacts.nameFor(context, c.number)
                text(if (who != null) "${getString(R.string.calling)} $who · ${c.number}" else "${getString(R.string.calling)} ${c.number}")
                text(Format.status(c.status), bold = true)
                timerViews[c.callSid] = text("")
                redButton(getString(R.string.end_call)) { end(null, c.callSid, it) }
            })
        }
    }

    private fun tick() {
        if (!::updated.isInitialized) return
        val data = live
        val age = if (fetchedAtMs == 0L) null else (System.currentTimeMillis() - fetchedAtMs) / 1000
        updated.text = buildString {
            append(if (age == null) getString(R.string.loading) else getString(R.string.last_updated, Format.ago(age)))
            if (data?.stale == true) append(" · ").append(getString(R.string.stale))
            lastError?.let { append("\n").append(it) }
        }
        val now = System.currentTimeMillis() / 1000.0 + serverOffset
        for (c in data?.calls.orEmpty()) {
            val tv = timerViews[c.callSid] ?: continue
            val dialed = c.dialedAt
            tv.text = when {
                c.answeredAt != null -> {
                    val rang = if (dialed != null) Format.duration((c.answeredAt - dialed).toInt().coerceAtLeast(0)) else "—"
                    getString(R.string.timer_talking, Format.duration((now - c.answeredAt).toInt().coerceAtLeast(0)), rang)
                }
                dialed != null -> getString(R.string.timer_ringing, Format.duration((now - dialed).toInt().coerceAtLeast(0)))
                else -> getString(R.string.timer_queued)
            }
        }
    }

    /** agent = "all", or callSid = one call. */
    private fun end(agent: String?, callSid: String?, button: Button) {
        val s = settings ?: return
        val original = button.text
        button.isEnabled = false; button.text = getString(R.string.ending)
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { if (callSid != null) Api(s).endCall(callSid) else Api(s).end(agent!!) }
            button.isEnabled = true; button.text = original
            Toast.makeText(requireContext(), r.message, Toast.LENGTH_LONG).show()
            fetch()
        }
    }
}
