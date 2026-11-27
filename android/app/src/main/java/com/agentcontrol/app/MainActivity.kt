package com.agentcontrol.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The list of agents, each with a big red "End call" button. */
class MainActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var refresher: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SecureStore.load(this) == null) {
            // First launch: go and set things up.
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(R.id.root).padForSystemBars()
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        list = findViewById(R.id.agent_list)
        status = findViewById(R.id.status)
        refresher = findViewById(R.id.refresher)
        refresher.setOnRefreshListener { loadAgents() }

        val endAll = findViewById<Button>(R.id.end_all)
        endAll.setOnClickListener { endCall("all", getString(R.string.all_agents), endAll) }
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) loadAgents()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> { loadAgents(); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadAgents() {
        val settings = SecureStore.load(this) ?: return
        refresher.isRefreshing = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { Result.success(CallControl(settings).agents()) } catch (e: Exception) { Result.failure(e) }
            }
            refresher.isRefreshing = false
            list.removeAllViews()
            result.onSuccess { agents ->
                status.text = if (agents.isEmpty()) getString(R.string.no_agents) else ""
                for (agent in agents) addAgentRow(agent)
            }.onFailure {
                status.text = it.message
            }
        }
    }

    private fun addAgentRow(agent: CallControl.Agent) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_agent, list, false)
        row.findViewById<TextView>(R.id.agent_name).text = agent.name
        val button = row.findViewById<Button>(R.id.end_call)
        button.contentDescription = getString(R.string.end_call_for, agent.name)
        button.setOnClickListener { endCall(agent.id, agent.name, button) }
        list.addView(row)
    }

    private fun endCall(agentId: String, label: String, button: Button) {
        val settings = SecureStore.load(this) ?: return
        val originalText = button.text
        button.isEnabled = false
        button.text = getString(R.string.ending)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { CallControl(settings).end(agentId) }
            button.isEnabled = true
            button.text = originalText
            status.text = result.message
            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
        }
    }
}
