package com.agentcontrol.app

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.agentcontrol.app.Ui.redButton
import com.agentcontrol.app.Ui.small
import com.agentcontrol.app.Ui.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Same as before: "End all calls" plus a big red "End call" button per agent. */
class AgentsFragment : BaseFragment() {

    override fun header(): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), 0)
        redButton(getString(R.string.end_all)) { endCall("all", it) }.apply {
            setBackgroundColor(context.getColor(R.color.end_red_dark)); minHeight = Ui.dp(context, 80)
        }
    }

    override fun refresh() = load({ it.agents() }) { agents ->
        content.removeAllViews()
        if (agents.isEmpty()) status.text = getString(R.string.no_agents)
        for (a in agents) {
            content.addView(Ui.card(requireContext()) {
                title(a.name)
                redButton(getString(R.string.end_call)) { endCall(a.id, it) }.contentDescription = getString(R.string.end_call_for, a.name)
            })
        }
        content.addView(LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; small(getString(R.string.pull_hint)) })
    }

    private fun endCall(agentId: String, button: Button) {
        val s = settings ?: return
        val original = button.text
        button.isEnabled = false; button.text = getString(R.string.ending)
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { Api(s).end(agentId) }
            button.isEnabled = true; button.text = original
            status.text = r.message
            Toast.makeText(requireContext(), r.message, Toast.LENGTH_LONG).show()
        }
    }
}
