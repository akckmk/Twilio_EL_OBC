package com.agentcontrol.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.util.Pair
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.agentcontrol.app.Ui.small
import com.agentcontrol.app.Ui.text
import com.agentcontrol.app.Ui.title
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/** Every past call, newest first. Filter by agent, dates and outcome; search by name or number. */
class HistoryFragment : Fragment(), Refreshable {
    private var from = Format.daysAgo(30)
    private var to = Format.today()
    private var agentFilter: String? = null
    private var outcomeFilter: String? = null
    private var query = ""
    private var all: List<Api.CallItem> = emptyList()
    private var history: Api.History? = null

    private lateinit var info: TextView
    private lateinit var rangeBtn: MaterialButton
    private lateinit var agentBtn: MaterialButton
    private lateinit var outcomeBtn: MaterialButton
    private lateinit var refresher: SwipeRefreshLayout
    private val adapter = Adapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val c = requireContext()
        val pad = Ui.dp(c, 16)
        fun btn(onClick: (View) -> Unit) = MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            .apply { isAllCaps = false; setOnClickListener(onClick) }
        rangeBtn = btn { pickRange() }
        agentBtn = btn { v -> pick(v, listOf(null) + all.map { it.agentName }.distinct().sorted(), { it ?: getString(R.string.all_agents) }) { agentFilter = it; apply() } }
        outcomeBtn = btn { v -> pick(v, listOf(null) + Format.OUTCOMES, { it?.let(Format::outcome) ?: getString(R.string.all_outcomes) }) { outcomeFilter = it; apply() } }
        val search = EditText(c).apply {
            hint = getString(R.string.search_hint)
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); apply() }
            })
        }
        info = TextView(c).apply { alpha = 0.75f }
        val filters = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(c, 4) } }
            addView(agentBtn, lp()); addView(outcomeBtn, lp())
        }
        val list = RecyclerView(c).apply { layoutManager = LinearLayoutManager(c); adapter = this@HistoryFragment.adapter }
        refresher = SwipeRefreshLayout(c).apply { addView(list); setOnRefreshListener { refresh() } }
        updateButtons()
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(c, 4), pad, 0)
            addView(rangeBtn); addView(filters); addView(search); addView(info)
            addView(refresher, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onResume() {
        super.onResume()
        if (history == null) showCached()
        refresh()
    }

    // ---- cache (encrypted on the phone) so the list shows instantly

    private fun showCached() {
        val text = SecureStore.readFile(requireContext(), CACHE) ?: return
        runCatching {
            val o = JSONObject(text)
            if (o.optString("from") == from && o.optString("to") == to) show(Api.parseHistory(o), fromCache = true)
        }
    }

    override fun refresh() {
        val s = SecureStore.load(requireContext()) ?: return
        refresher.isRefreshing = true
        val (f, t) = from to to
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Api(s).callsRaw(f, t).also { SecureStore.writeFile(requireContext(), CACHE, it.toString()) } }
            }
            if (!isAdded) return@launch
            refresher.isRefreshing = false
            r.onSuccess { show(Api.parseHistory(it), fromCache = false) }
                .onFailure { info.text = "${it.message}" + (history?.let { "\n" + getString(R.string.showing_saved) } ?: "") }
        }
    }

    private fun show(h: Api.History, fromCache: Boolean) {
        history = h; all = h.items; apply()
    }

    // ---- filtering

    private fun apply() {
        if (!::info.isInitialized) return
        val ctx = requireContext()
        val q = query.lowercase()
        val qDigits = query.filter { it.isDigit() }
        val shown = all.filter { i ->
            (agentFilter == null || i.agentName == agentFilter) &&
                (outcomeFilter == null || i.outcome == outcomeFilter) &&
                (q.isEmpty() || (Contacts.nameFor(ctx, i.number)?.lowercase()?.contains(q) == true) ||
                    (qDigits.length >= 3 && i.number.filter { it.isDigit() }.contains(qDigits)) ||
                    i.agentName.lowercase().contains(q))
        }
        adapter.items = shown
        adapter.notifyDataSetChanged()
        val h = history
        info.text = buildString {
            append(getString(R.string.history_count, shown.size, all.size))
            if (h != null) {
                append(" · ").append(getString(R.string.last_updated, Format.ago((System.currentTimeMillis() - h.cachedAt) / 1000)))
                if (h.stale) append(" · ").append(getString(R.string.stale))
                if (h.truncated) append("\n").append(getString(R.string.truncated))
            }
        }
        updateButtons()
    }

    private fun updateButtons() {
        rangeBtn.text = getString(R.string.range_label, from, to)
        agentBtn.text = agentFilter ?: getString(R.string.all_agents)
        outcomeBtn.text = outcomeFilter?.let(Format::outcome) ?: getString(R.string.all_outcomes)
    }

    private fun <T> pick(anchor: View, options: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
        val menu = PopupMenu(requireContext(), anchor)
        options.forEachIndexed { i, o -> menu.menu.add(0, i, i, label(o)) }
        menu.setOnMenuItemClickListener { onPick(options[it.itemId]); true }
        menu.show()
    }

    private fun pickRange() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.pick_dates)
            .setSelection(Pair(utcMillis(from), utcMillis(to)))
            .build()
        picker.addOnPositiveButtonClickListener { sel ->
            from = utcDate(sel.first); to = utcDate(sel.second)
            history = null; all = emptyList(); apply(); refresh()
        }
        picker.show(parentFragmentManager, "range")
    }

    // ---- list

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Api.CallItem> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(LinearLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                orientation = LinearLayout.VERTICAL
            }) {}
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val i = items[pos]
            val holder = h.itemView as LinearLayout
            holder.removeAllViews()
            val ctx = holder.context
            holder.addView(Ui.card(ctx) {
                title("${i.agentName} → ${Contacts.label(ctx, i.number)}")
                if (Contacts.nameFor(ctx, i.number) != null) small(i.number)
                text(Format.dateTime(i.startTime))
                text("${Format.outcome(i.outcome)} · ${getString(R.string.rang)} ${Format.duration(i.ringSecs)} · ${getString(R.string.lasted)} ${Format.duration(i.talkSecs)}")
                val twilio = if (i.twilioPricePending) getString(R.string.pending) else Format.usdPrecise(i.twilioUsd)
                small("${getString(R.string.credits)} ${Format.credits(i.credits)} · ElevenLabs ${Format.usd(i.elevenUsd)} · Twilio $twilio")
                i.title?.let { small(it) }
            }.apply {
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(ctx, CallDetailActivity::class.java)
                        .putExtra(CallDetailActivity.EXTRA_SID, i.callSid)
                        .putExtra(CallDetailActivity.EXTRA_CONVERSATION, i.conversationId))
                }
            })
        }
    }

    companion object {
        private const val CACHE = "history"
        fun utcMillis(date: String) = java.time.LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        fun utcDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
    }
}
