package com.androclaw.agent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.androclaw.agent.MainActivity
import com.androclaw.agent.data.AppDatabase
import com.androclaw.agent.data.SecurePreferences
import com.androclaw.agent.data.TaskEntity
import com.androclaw.agent.data.TaskStatus
import com.androclaw.agent.llm.LlmProviderFactory
import com.androclaw.agent.perception.ScreenCapture
import com.androclaw.agent.safety.SafetyGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class AgentService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "agent_channel"
        const val ACTION_STOP = "com.androclaw.agent.ACTION_STOP"
    }

    private val binder = AgentBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateObserverJob: Job? = null

    lateinit var agentLoop: AgentLoop
        private set
    lateinit var safetyGuard: SafetyGuard
        private set
    lateinit var screenCapture: ScreenCapture
        private set
    lateinit var routineManager: RoutineManager
        private set

    inner class AgentBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Agent ready"))

        val prefs = SecurePreferences.getInstance(applicationContext)
        val db = AppDatabase.getInstance(applicationContext)
        safetyGuard = SafetyGuard(prefs)
        screenCapture = ScreenCapture()
        val llmProvider = LlmProviderFactory.create(prefs)
        agentLoop = AgentLoop(llmProvider, safetyGuard, prefs, screenCapture)
        routineManager = RoutineManager(db.routineDao())

        // Observe agent state to update notification and persist task
        stateObserverJob = agentLoop.state
            .onEach { state -> handleStateChange(state, prefs, db) }
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            agentLoop.stop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        agentLoop.stop()
        stateObserverJob?.cancel()
    }

    private fun handleStateChange(
        state: AgentState,
        prefs: SecurePreferences,
        db: AppDatabase
    ) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        when (state) {
            is AgentState.Planning -> {
                nm.notify(NOTIFICATION_ID, buildNotification("Planning: ${state.goal.take(40)}"))
            }
            is AgentState.Executing -> {
                nm.notify(NOTIFICATION_ID, buildNotification("Running: ${state.goal.take(40)}…"))
            }
            is AgentState.Completed -> {
                nm.notify(NOTIFICATION_ID, buildNotification("Done ✓ ${state.goal.take(40)}"))
                serviceScope.launch {
                    db.taskDao().insert(
                        TaskEntity(
                            goal = state.goal,
                            status = TaskStatus.COMPLETED.name,
                            steps = state.steps,
                            completedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            is AgentState.Failed -> {
                nm.notify(NOTIFICATION_ID, buildNotification("Failed: ${state.reason.take(40)}"))
                serviceScope.launch {
                    db.taskDao().insert(
                        TaskEntity(
                            goal = state.goal,
                            status = TaskStatus.FAILED.name,
                            steps = state.steps,
                            errorMessage = state.reason
                        )
                    )
                }
            }
            is AgentState.Stopped -> {
                nm.notify(NOTIFICATION_ID, buildNotification("Stopped"))
                serviceScope.launch {
                    db.taskDao().insert(
                        TaskEntity(
                            goal = state.goal,
                            status = TaskStatus.STOPPED.name,
                            steps = state.steps
                        )
                    )
                }
            }
            else -> {
                nm.notify(NOTIFICATION_ID, buildNotification("Agent ready"))
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPi = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AgentService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AndroClaw")
            .setContentText(text)
            .setContentIntent(mainPi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "AndroClaw agent status"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
