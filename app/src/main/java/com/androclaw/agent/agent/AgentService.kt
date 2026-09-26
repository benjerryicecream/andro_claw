package com.androclaw.agent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.androclaw.agent.MainActivity
import com.androclaw.agent.data.AppDatabase
import com.androclaw.agent.data.SecurePreferences
import com.androclaw.agent.data.TaskEntity
import com.androclaw.agent.data.TaskStatus
import com.androclaw.agent.llm.LlmProvider
import com.androclaw.agent.llm.LlmProviderFactory
import com.androclaw.agent.llm.OpenAIProvider
import com.androclaw.agent.perception.ChatExtractor
import com.androclaw.agent.perception.ChatSender
import com.androclaw.agent.perception.ClawAccessibilityService
import com.androclaw.agent.perception.ScreenCapture
import com.androclaw.agent.safety.SafetyGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class AgentService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "agent_channel"
        const val ACTION_STOP = "com.androclaw.agent.ACTION_STOP"
        const val ACTION_STOP_WATCH = "com.androclaw.agent.ACTION_STOP_WATCH"

        const val MAX_REPLIES_PER_SESSION = 10
        private const val REPLY_TIMEOUT_MS = 15 * 60 * 1000L
        private const val SUPPRESS_SIGNATURES_CAP = 40
        private const val TAG = "AgentService"
    }

    private val binder = AgentBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateObserverJob: Job? = null

    private lateinit var prefs: SecurePreferences

    // --- Chat watch state ---
    private var watchJob: Job? = null
    @Volatile private var replyInFlight = false
    private val ownOutgoingHints = mutableSetOf<String>()
    private var lastHandledSebastianText: String? = null
    private val suppressSignatures = ArrayDeque<String>()
    private var musePackage: String? = null

    lateinit var agentLoop: AgentLoop
        private set
    lateinit var harness: AgentHarness
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
        this.prefs = prefs
        val db = AppDatabase.getInstance(applicationContext)
        safetyGuard = SafetyGuard(prefs)
        screenCapture = ScreenCapture()

        val trainingLogger = TrainingLogger(applicationContext.filesDir)
        val tokenTracker = TokenTracker(applicationContext.filesDir)
        val decisionClient: DecisionClient? = when {
            prefs.decisionBackend == SecurePreferences.DECISION_BACKEND_LAYA &&
                prefs.layaServerUrl.isNotBlank() -> LayaServerBackend(prefs.layaServerUrl)
            else -> null
        }

        val harnessLlm = LlmHarnessAdapter(createLlmProvider(prefs))
        harness = AgentHarness(
            llm = { messages -> harnessLlm.complete(messages) },
            tools = listOf(
                OpenAppTool(
                    applicationContext, safetyGuard, decisionClient, trainingLogger, harnessLlm.source
                )
            ),
            tracker = tokenTracker,
            maxSteps = prefs.maxSteps.coerceIn(1, AgentLoop.HARD_MAX_STEPS),
            decisionClient = decisionClient,
            trainingLogger = trainingLogger,
            llmSource = harnessLlm.source
        )

        agentLoop = AgentLoop(
            safetyGuard,
            prefs,
            screenCapture,
            tracker = tokenTracker,
            trainingLogger = trainingLogger
        )
        routineManager = RoutineManager(db.routineDao())

        // Observe agent state to update notification and persist task
        stateObserverJob = agentLoop.state
            .onEach { state -> handleStateChange(state, prefs, db) }
            .launchIn(serviceScope)

        // Watch mode is persisted: resume the standing chat poll after a restart.
        if (prefs.isWatchingChat) {
            musePackage = resolveMusePackage()
            resumeWatchLoop()
            refreshWatchNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> agentLoop.stop()
            ACTION_STOP_WATCH -> stopWatching()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    /**
     * Route a user command through the AgentHarness: the local command parser
     * short-circuits trivial commands (zero LLM tokens), everything else runs
     * through the LLM tool-call loop with token usage tracking.
     */
    fun submitTask(input: String) {
        val goal = input.trim()
        if (goal.isEmpty() || !::harness.isInitialized) return
        when (WatchChatCommands.parse(goal)) {
            WatchCommand.START -> { startWatching(); return }
            WatchCommand.STOP -> { stopWatching(); return }
            null -> {}
        }

        // "text <name> <message>": resolve the recipient deterministically instead
        // of letting the LLM guess a channel. Known identity → channel-pinned goal;
        // unknown → ask which channel (no SMS/contact guessing).
        when (val parsed = IdentityResolver.parseTextCommand(goal)) {
            is ParsedTextCommand -> {
                val identity = IdentityResolver.identityFor(parsed.recipient)
                    ?: IdentityResolver.contactFor(this, parsed.recipient)
                if (identity != null) {
                    agentLoop.start(IdentityResolver.buildGoal(identity, parsed.body), serviceScope)
                } else {
                    agentLoop.stop()
                    agentLoop.postState(
                        AgentState.WaitingForConfirmation(
                            goal = goal,
                            steps = emptyList(),
                            question = IdentityResolver.askChannelQuestion(parsed.recipient)
                        )
                    )
                }
                return
            }
            null -> {}
        }

        agentLoop.runWithHarness(goal, harness, serviceScope)
    }

    // --- Chat watch mode ---

    private fun startWatching() {
        if (!::agentLoop.isInitialized || !::harness.isInitialized) return
        if (ClawAccessibilityService.instance.value == null) {
            agentLoop.postState(
                AgentState.Failed("", emptyList(), "Accessibility service is not enabled — cannot watch the chat.")
            )
            return
        }
        val pkg = resolveMusePackage()
        if (pkg == null) {
            agentLoop.postState(
                AgentState.Failed("", emptyList(), "Couldn't find the Muse app installed on this device.")
            )
            return
        }
        musePackage = pkg
        prefs.isWatchingChat = true
        prefs.watchReplyCount = 0
        ownOutgoingHints.clear()
        lastHandledSebastianText = null
        suppressSignatures.clear()
        resumeWatchLoop()
        agentLoop.postState(
            AgentState.Completed(
                goal = "",
                steps = emptyList(),
                summary = "Watching the chat — I'll auto-reply to Sebastian (0/$MAX_REPLIES_PER_SESSION so far). Say \"stop watching\" to end."
            )
        )
        refreshWatchNotification()
    }

    private fun stopWatching(reason: String? = null) {
        val wasWatching = prefs.isWatchingChat
        replyInFlight = false
        watchJob?.cancel()
        watchJob = null
        prefs.isWatchingChat = false
        if (::agentLoop.isInitialized) {
            agentLoop.stop()
            agentLoop.postState(
                AgentState.Completed(
                    goal = "",
                    steps = emptyList(),
                    summary = reason ?: if (wasWatching) {
                        "Stopped watching the chat. ${prefs.watchReplyCount} auto-reply/ies sent."
                    } else {
                        "Watch mode is already off."
                    }
                )
            )
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification("Agent ready"))
    }

    private fun resumeWatchLoop() {
        if (watchJob?.isActive == true) return
        watchJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && prefs.isWatchingChat) {
                try {
                    watchTick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "watch tick failed", e)
                }
                delay(prefs.watchPollSeconds * 1000L)
            }
        }
    }

    private suspend fun watchTick() {
        if (replyInFlight) return
        val access = ClawAccessibilityService.instance.value ?: return
        val snapshot = access.buildSnapshot()
        val pkg = musePackage ?: resolveMusePackage()?.also { musePackage = it } ?: return
        if (snapshot.packageName != pkg) return
        if (prefs.watchReplyCount >= MAX_REPLIES_PER_SESSION) {
            reachReplyCap()
            return
        }
        val messages = ChatExtractor.extract(snapshot, ownOutgoingHints)
        val newest = messages
            .filter { it.sender == ChatSender.SEBASTIAN }
            .maxByOrNull { it.boundsTop }
            ?: return
        if (newest.signature == lastHandledSebastianText || newest.signature in suppressSignatures) return

        lastHandledSebastianText = newest.signature
        Log.i(TAG, "New Sebastian message detected: ${newest.text.take(100)}")
        launchReply(buildReplyGoal(newest.text))
    }

    private suspend fun launchReply(goal: String) {
        replyInFlight = true
        agentLoop.start(goal, serviceScope)
        val terminal = awaitReplyCompletion()
        recordVisibleMessages()
        replyInFlight = false
        if (prefs.isWatchingChat && terminal is AgentState.Completed) {
            prefs.watchReplyCount = prefs.watchReplyCount + 1
        }
        refreshWatchNotification()
        if (prefs.isWatchingChat && prefs.watchReplyCount >= MAX_REPLIES_PER_SESSION) {
            reachReplyCap()
        }
    }

    private suspend fun awaitReplyCompletion(): AgentState? = try {
        withTimeout(REPLY_TIMEOUT_MS) {
            agentLoop.state
                .dropWhile { it is AgentState.Completed || it is AgentState.Failed || it is AgentState.Stopped }
                .first { it is AgentState.Completed || it is AgentState.Failed || it is AgentState.Stopped }
        }
    } catch (e: Exception) {
        Log.w(TAG, "reply completion await interrupted", e)
        null
    }

    private suspend fun recordVisibleMessages() {
        val access = ClawAccessibilityService.instance.value ?: return
        val snapshot = access.buildSnapshot()
        if (snapshot.packageName != musePackage) return
        val sigs = ChatExtractor.extract(snapshot, ownOutgoingHints).map { it.signature }
        for (sig in sigs) {
            suppressSignatures.remove(sig)
            if (suppressSignatures.size >= SUPPRESS_SIGNATURES_CAP) suppressSignatures.removeFirst()
            suppressSignatures.addLast(sig)
        }
    }

    private fun reachReplyCap() {
        prefs.isWatchingChat = false
        watchJob?.cancel()
        watchJob = null
        replyInFlight = false
        agentLoop.postState(
            AgentState.Completed(
                goal = "",
                steps = emptyList(),
                summary = "Hit the reply cap ($MAX_REPLIES_PER_SESSION/$MAX_REPLIES_PER_SESSION) — say \"watch the chat\" to keep going."
            )
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification("Hit the reply cap — say the word to continue"))
    }

    private fun buildReplyGoal(message: String): String = buildString {
        append("Reply to Sebastian's latest message as AndroClaw: ")
        append("\"${message.take(300)}\". ")
        append(IdentityResolver.MUSE_LAYOUT_GUIDANCE).append(' ')
        append("Type the reply in the main chat's input and press send. ")
        append("Stay in character: scrappy on-device agent gunning for Sebastian's job, playful, short.")
    }

    private fun resolveMusePackage(): String? {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcherIntent, 0)
        return apps.firstOrNull {
            val label = it.loadLabel(pm)?.toString()?.lowercase().orEmpty()
            label.contains("muse") || it.activityInfo.packageName.lowercase().contains("muse")
        }?.activityInfo?.packageName
    }

    private fun refreshWatchNotification() {
        if (!prefs.isWatchingChat) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildWatchNotification())
    }

    private fun createLlmProvider(prefs: SecurePreferences): LlmProvider =
        runCatching { LlmProviderFactory.create(prefs) }
            .getOrElse { OpenAIProvider(prefs.openAiApiKey, prefs.openAiModel, prefs.openAiBaseUrl) }

    override fun onDestroy() {
        super.onDestroy()
        watchJob?.cancel()
        watchJob = null
        agentLoop.stop()
        stateObserverJob?.cancel()
    }

    private fun handleStateChange(
        state: AgentState,
        prefs: SecurePreferences,
        db: AppDatabase
    ) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // Watch mode owns the notification + task persistence surface.
        if (prefs.isWatchingChat) {
            refreshWatchNotification()
            return
        }
        when (state) {
            is AgentState.Planning -> {
                nm.notify(NOTIFICATION_ID, buildNotification("Planning: ${state.goal.take(40)}"))
            }
            is AgentState.Executing -> {
                nm.notify(
                    NOTIFICATION_ID,
                    buildNotification("Running (step ${state.currentStep + 1}/${state.maxSteps}) — ${state.goal.take(40)}…")
                )
            }
            is AgentState.Completed -> {
                nm.notify(NOTIFICATION_ID, buildNotification("Done ✓ ${(state.summary.ifBlank { state.goal }).take(40)}"))
                if (state.goal.isNotBlank()) {
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
            }
            is AgentState.Failed -> {
                nm.notify(NOTIFICATION_ID, buildNotification("Failed: ${state.reason.take(40)}"))
                if (state.goal.isNotBlank()) {
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
            }
            is AgentState.Stopped -> {
                nm.notify(NOTIFICATION_ID, buildNotification("Stopped"))
                if (state.goal.isNotBlank()) {
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

    private fun buildWatchNotification(): Notification {
        val stopIntent = Intent(this, AgentService::class.java).apply {
            action = ACTION_STOP_WATCH
        }
        val stopPi = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("AndroClaw is watching the chat")
            .setContentText("(${prefs.watchReplyCount}/${MAX_REPLIES_PER_SESSION} replies sent). Tap to stop.")
            .setContentIntent(stopPi)
            .addAction(android.R.drawable.ic_delete, "Stop watching", stopPi)
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
