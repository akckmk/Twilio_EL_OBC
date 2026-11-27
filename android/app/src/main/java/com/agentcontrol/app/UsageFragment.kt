package com.agentcontrol.app

import android.widget.LinearLayout
import android.widget.ProgressBar
import com.agentcontrol.app.Ui.big
import com.agentcontrol.app.Ui.row
import com.agentcontrol.app.Ui.small
import com.agentcontrol.app.Ui.title
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** ElevenLabs credits, Twilio balance and spend, and cost totals by agent / day / week / month. */
class UsageFragment : BaseFragment() {

    override fun refresh() = load({ api ->
        // Costs: the last 92 days (the longest range the backend serves in one go).
        api.usage() to api.analytics(Format.daysAgo(91), Format.today())
    }) { (usage, analytics) -> render(usage, analytics) }

    private fun render(u: JSONObject, a: JSONObject) {
        val c = requireContext()
        content.removeAllViews()
        if (u.has("cachedAt")) {
            status.text = getString(R.string.usage_updated, Format.ago((System.currentTimeMillis() - u.optLong("cachedAt")) / 1000))
        }

        val el = u.optJSONObject("elevenlabs") ?: JSONObject()
        content.addView(Ui.card(c) {
            title(getString(R.string.elevenlabs_credits))
            if (el.has("error")) {
                small(el.optString("error"))
            } else {
                val used = el.optDouble("creditsUsed", 0.0); val total = el.optDouble("creditsTotal", 0.0)
                big(getString(R.string.credits_left, Format.credits(el.optDouble("creditsLeft", 0.0))))
                addView(ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000; progress = if (total > 0) ((used / total) * 1000).toInt() else 0
                })
                row(getString(R.string.used_this_period), Format.credits(used))
                row(getString(R.string.plan_total), Format.credits(total))
                el.optString("plan").takeIf { it.isNotEmpty() && it != "null" }?.let { row(getString(R.string.plan), it) }
                if (!el.isNull("resetsAt")) row(getString(R.string.resets_on), Format.date(el.optDouble("resetsAt")))
            }
        })

        val tw = u.optJSONObject("twilio") ?: JSONObject()
        content.addView(Ui.card(c) {
            title("Twilio")
            row(getString(R.string.balance), if (tw.isNull("balance")) "—" else Format.usd(tw.optDouble("balance")))
            row(getString(R.string.spend_this_month), if (tw.isNull("spendThisMonth")) "—" else Format.usd(tw.optDouble("spendThisMonth")))
            tw.optString("error").takeIf { it.isNotEmpty() && it != "null" }?.let { small(it) }
            small(getString(R.string.twilio_balance_note))
        })

        val pending = a.optInt("twilioPricesPending")
        content.addView(Ui.card(c) {
            title(getString(R.string.cost_by_agent))
            small(getString(R.string.last_92_days))
            rows(a.optJSONArray("byAgent"), "agentName")
            if (pending > 0) small(getString(R.string.prices_pending, pending))
        })
        val totals = a.optJSONObject("totals") ?: JSONObject()
        content.addView(Ui.card(c) { title(getString(R.string.by_day)); rows(totals.optJSONArray("byDay"), "period", limit = 14) })
        content.addView(Ui.card(c) { title(getString(R.string.by_week)); rows(totals.optJSONArray("byWeek"), "period", limit = 8, prefix = getString(R.string.week_of)) })
        content.addView(Ui.card(c) { title(getString(R.string.by_month)); rows(totals.optJSONArray("byMonth"), "period", limit = 3) })
        content.addView(Ui.card(c) { small(getString(R.string.cost_note)) })
    }

    private fun LinearLayout.rows(arr: JSONArray?, labelKey: String, limit: Int = 50, prefix: String = "") {
        if (arr == null || arr.length() == 0) { small(getString(R.string.no_calls_yet)); return }
        for (i in 0 until minOf(limit, arr.length())) {
            val r = arr.getJSONObject(i)
            var label = r.optString(labelKey)
            if (labelKey == "period" && label.length == 10) label = runCatching { LocalDate.parse(label).let { "${it.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase)} ${it.dayOfMonth}" } }.getOrDefault(label)
            row("$prefix$label  (${resources.getQuantityString(R.plurals.n_calls, r.optInt("calls"), r.optInt("calls"))})", Format.usd(r.optDouble("totalUsd")))
            small("${getString(R.string.credits)} ${Format.credits(r.optDouble("credits"))} · ElevenLabs ${Format.usd(r.optDouble("elevenUsd"))} · Twilio ${Format.usd(r.optDouble("twilioUsd"))}" +
                if (r.optInt("twilioPending") > 0) " (+${r.optInt("twilioPending")} ${getString(R.string.pending)})" else "")
        }
    }
}
