package com.androclaw.agent

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.androclaw.agent.agent.AgentService

/**
 * Debug-build-only hook:
 *   adb shell am start -n com.androclaw.agent.debug/com.androclaw.agent.DebugTestActivity \
 *       --es goal "<command>"
 * Submits the goal through the exact same AgentService path as the chat input,
 * skipping the device keystroke/IME layer so end-to-end runs are repeatable.
 * Not present in release builds (lives in src/debug).
 */
class DebugTestActivity : Activity() {

    private lateinit var serviceIntent: Intent
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AgentService.AgentBinder).getService()
            val goal = intent.getStringExtra(EXTRA_GOAL)?.trim().orEmpty()
            Log.i(TAG, "submit goal=$goal")
            if (goal.isNotEmpty()) service.submitTask(goal)
            finish()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceIntent = Intent(this, AgentService::class.java)
        startForegroundService(serviceIntent)
        bound = bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        // Safety net: never leave the translucent activity around.
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 10_000L)
    }

    override fun onDestroy() {
        if (bound) {
            try {
                unbindService(connection)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DebugTestActivity"
        private const val EXTRA_GOAL = "goal"
    }
}