package com.androclaw.agent.ui.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androclaw.agent.agent.AgentService
import com.androclaw.agent.agent.AgentState
import com.androclaw.agent.agent.RoutineManager
import com.androclaw.agent.data.AppDatabase
import com.androclaw.agent.data.SecurePreferences
import com.androclaw.agent.data.TaskEntity
import com.androclaw.agent.perception.ClawAccessibilityService
import com.androclaw.agent.safety.ConfirmationRequest
import com.androclaw.agent.safety.SafetyGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val agentState: AgentState = AgentState.Idle,
    val isAccessibilityEnabled: Boolean = false,
    val pendingConfirmation: ConfirmationRequest? = null,
    val taskHistory: List<TaskEntity> = emptyList(),
    val inputText: String = "",
    val isListening: Boolean = false
)

class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var agentService: AgentService? = null
    private var serviceConnection: ServiceConnection? = null

    fun bindService(context: Context) {
        val intent = Intent(context, AgentService::class.java)
        context.startForegroundService(intent)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = (binder as AgentService.AgentBinder).getService()
                agentService = service

                // Observe agent state
                viewModelScope.launch {
                    service.agentLoop.state.collect { state ->
                        _uiState.value = _uiState.value.copy(agentState = state)
                    }
                }

                // Observe pending confirmation
                viewModelScope.launch {
                    service.safetyGuard.pendingConfirmation.collect { req ->
                        _uiState.value = _uiState.value.copy(pendingConfirmation = req)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                agentService = null
            }
        }
        serviceConnection = conn
        context.bindService(intent, conn, Context.BIND_AUTO_CREATE)

        // Observe accessibility service state
        viewModelScope.launch {
            ClawAccessibilityService.instance.collect { svc ->
                _uiState.value = _uiState.value.copy(isAccessibilityEnabled = svc != null)
            }
        }
    }

    fun unbindService(context: Context) {
        serviceConnection?.let { context.unbindService(it) }
        serviceConnection = null
    }

    fun loadHistory(context: Context) {
        val db = AppDatabase.getInstance(context)
        viewModelScope.launch {
            db.taskDao().getRecentTasks(50).collect { tasks ->
                _uiState.value = _uiState.value.copy(taskHistory = tasks)
            }
        }
    }

    fun onInputChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun sendTask(context: Context) {
        val goal = _uiState.value.inputText.trim()
        if (goal.isBlank()) return
        _uiState.value = _uiState.value.copy(inputText = "")

        val service = agentService ?: run {
            // Service not bound yet — bind first
            bindService(context)
            return
        }
        service.submitTask(goal)
    }

    fun stopTask() {
        agentService?.agentLoop?.stop()
    }

    fun confirmAction() {
        agentService?.safetyGuard?.confirm()
    }

    fun cancelAction() {
        agentService?.safetyGuard?.cancel()
    }

    fun saveAsRoutine(context: Context, name: String, triggerPhrase: String) {
        val state = _uiState.value.agentState
        val (goal, steps) = when (state) {
            is AgentState.Completed -> Pair(state.goal, state.steps)
            is AgentState.Stopped -> Pair(state.goal, state.steps)
            else -> return
        }
        viewModelScope.launch {
            agentService?.routineManager?.saveAsRoutine(
                name = name,
                description = "Saved from: $goal",
                triggerPhrase = triggerPhrase,
                steps = steps
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Connection is unbound in Activity lifecycle
    }
}
