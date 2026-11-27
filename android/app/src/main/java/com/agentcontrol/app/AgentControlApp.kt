package com.agentcontrol.app

import android.app.Application

class AgentControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLock.install()
        Notifier.createChannels(this)
        // A recording left behind by a crash is deleted on the next start.
        cacheDir.listFiles { f -> f.name.startsWith("rec") && f.name.endsWith(".audio") }?.forEach { it.delete() }
    }
}
