package com.agentcontrol.app

import android.view.View
import android.widget.LinearLayout
import androidx.core.util.Pair
import com.agentcontrol.app.Ui.big
import com.agentcontrol.app.Ui.chart
import com.agentcontrol.app.Ui.row
import com.agentcontrol.app.Ui.small
import com.agentcontrol.app.Ui.title
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import org.json.JSONArray
import org.json.JSONObject

/** Calls per day, answer rate, call length, credits and dollars by agent, busiest hours, outcomes. */
class AnalyticsFragment : BaseFragment() {
    private var from = Format.daysAgo(30)
    private var to = Format.today()
    private lateinit var rangeBtn: MaterialButton

    override fun header(): View = LinearLayout(requireContext()).apply {
        setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), 0)
        rangeBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false
            setOnClickListener {
                val p = MaterialDatePicker.Builder.dateRangePicker().setTitleText(R.string.pick_dates)
                    .setSelection(Pair(HistoryFragment.utcMillis(from), HistoryFragment.utcMillis(to))).build()
                p.addOnPositiveButtonClickListener { sel -> from = HistoryFragment.utcDate(sel.first); to = HistoryFragment.utcDate(sel.second); refresh() }
                p.show(parentFragmentManager, "range")
            }
        }
        addView(rangeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    override fun refresh() {
        rangeBtn.text = getString(R.string.range_label, from, to)
        load({ it.analytics(from, to) }) { render(it) }
    }

    private fun render(a: JSONObject) {
        val c = requireContext()
        content.removeAllViews()
        if (a.optBoolean("stale")) status.text = getString(R.string.stale)

        content.addView(Ui.card(c) {
            title(getString(R.string.overview))
            big(getString(R.string.n_calls, a.optInt("totalCalls")))
            row(getString(R.string.answer_rate), if (a.isNull("answerRate")) "—" else "${(a.optDouble("answerRate") * 100).toInt()}%")
            row(getString(R.string.avg_length), if (a.isNull("avgTalkSecs")) "—" else Format.duration(a.optInt("avgTalkSecs")))
        })

        val days = a.optJSONArray("callsPerDay") ?: JSONArray()
        content.addView(Ui.card(c) {
            title(getString(R.string.calls_per_day))
            small(getString(R.string.calls_per_day_legend))
            chart().setData(
                values(days, "calls"), labels(days, "day") { it.substring(5) }, overlay = values(days, "answered"),
            )
        })

        val agents = a.optJSONArray("byAgent") ?: JSONArray()
        content.addView(Ui.card(c) {
            title(getString(R.string.credits_by_agent))
            chart().setData(values(agents, "credits"), labels(agents, "agentName") { it.take(10) }) { Format.credits(it) }
            title(getString(R.string.dollars_by_agent))
            chart().setData(values(agents, "totalUsd"), labels(agents, "agentName") { it.take(10) }) { Format.usd(it) }
            for (i in 0 until agents.length()) agents.getJSONObject(i).let {
                row(it.optString("agentName"), "${it.optInt("calls")} calls · ${Format.credits(it.optDouble("credits"))} cr · ${Format.usd(it.optDouble("totalUsd"))}")
            }
        })

        val hours = a.optJSONArray("busiestHours") ?: JSONArray()
        content.addView(Ui.card(c) {
            title(getString(R.string.busiest_hours))
            chart().setData(values(hours, "calls"), (0 until 24).map { h -> if (h == 0) "12a" else if (h < 12) "${h}a" else if (h == 12) "12p" else "${h - 12}p" })
        })

        val outcomes = a.optJSONObject("outcomes") ?: JSONObject()
        content.addView(Ui.card(c) {
            title(getString(R.string.calls_by_outcome))
            for (o in Format.OUTCOMES) row(Format.outcome(o), outcomes.optInt(o).toString())
        })
    }

    private fun values(arr: JSONArray, key: String) = (0 until arr.length()).map { arr.getJSONObject(it).optDouble(key, 0.0) }
    private fun labels(arr: JSONArray, key: String, f: (String) -> String) = (0 until arr.length()).map { f(arr.getJSONObject(it).optString(key)) }
}
