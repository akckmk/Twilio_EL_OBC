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
        if (savedInstanceState == null) nav.selectedItemId = safeTab(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) findViewById<BottomNavigationView>(R.id.bottom_nav)?.selectedItemId = safeTab(intent)
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
        private val TABS = setOf(R.id.nav_agents, R.id.nav_live, R.id.nav_history, R.id.nav_usage, R.id.nav_analytics)

        /** Other apps can open this screen with any extras; only accept our own tab ids. */
        fun safeTab(intent: Intent): Int = try {
            intent.getIntExtra(EXTRA_TAB, R.id.nav_agents).takeIf { it in TABS } ?: R.id.nav_agents
        } catch (e: Exception) { R.id.nav_agents }
    }
}

interface Refreshable { fun refresh() }
