package com.androclaw.agent

import android.app.Application

class AgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // No global init needed — singletons lazy-initialize via getInstance(context)
    }
}
