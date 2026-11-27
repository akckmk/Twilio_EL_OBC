package com.agentcontrol.app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

/** The dashboard: Agents, Live, History, Usage and Analytics tabs. */
class MainActivity : SecureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SecureStore.load(this) == null) {
            // First launch (or after upgrading from the version that held provider keys).
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(R.id.root).padForSystemBars()
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { show(it.itemId); true }
        if (savedInstanceState == null) nav.selectedItemId = intent.getIntExtra(EXTRA_TAB, R.id.nav_agents)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getIntExtra(EXTRA_TAB, 0).takeIf { it != 0 }?.let {
            findViewById<BottomNavigationView>(R.id.bottom_nav)?.selectedItemId = it
        }
    }

    override fun onUnlocked() {
        CallWatchService.sync(this)
    }

    private fun show(id: Int) {
        val tag = id.toString()
        val fm = supportFragmentManager
        if (fm.findFragmentByTag(tag)?.isVisible == true) return
        val f: Fragment = fm.findFragmentByTag(tag) ?: when (id) {
            R.id.nav_live -> LiveFragment()
            R.id.nav_history -> HistoryFragment()
            R.id.nav_usage -> UsageFragment()
            R.id.nav_analytics -> AnalyticsFragment()
            else -> AgentsFragment()
        }
        fm.beginTransaction().replace(R.id.container, f, tag).commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> { (supportFragmentManager.findFragmentById(R.id.container) as? Refreshable)?.refresh(); true }
        R.id.action_contacts -> { startActivity(Intent(this, ContactsActivity::class.java)); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}

interface Refreshable { fun refresh() }
