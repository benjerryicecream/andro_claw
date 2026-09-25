package com.androclaw.agent.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.androclaw.agent.data.LlmProviderType
import com.androclaw.agent.data.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val selectedProvider: LlmProviderType = LlmProviderType.OPENAI,
    val openAiKey: String = "",
    val openAiModel: String = "gpt-4o",
    val openAiBaseUrl: String = "https://api.openai.com/v1",
    val anthropicKey: String = "",
    val anthropicModel: String = "claude-3-5-sonnet-20241022",
    val geminiKey: String = "",
    val geminiModel: String = "gemini-2.0-flash",
    val ollamaModel: String = "llama3.2",
    val ollamaBaseUrl: String = "http://10.0.2.2:11434",
    val maxSteps: Int = 15,
    val debugMode: Boolean = false,
    val confirmMessages: Boolean = true,
    val confirmCalls: Boolean = true,
    val confirmPayments: Boolean = true,
    val confirmDeletions: Boolean = true,
    val confirmSystemSettings: Boolean = true,
    val confirmPermissions: Boolean = true,
    val unrestrictedMode: Boolean = true,
    val useAllowlist: Boolean = false,
    val allowlistPackages: Set<String> = emptySet(),
    val blocklistPackages: Set<String> = emptySet(),
    val showFloatingOverlay: Boolean = false,
    val decisionBackend: String = "off",
    val layaServerUrl: String = "http://127.0.0.1:7710",
    val watchPollSeconds: Int = 20
)

class SettingsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var prefs: SecurePreferences? = null

    fun load(context: Context) {
        val p = SecurePreferences.getInstance(context)
        prefs = p
        _uiState.value = SettingsUiState(
            selectedProvider = LlmProviderType.valueOf(p.selectedProvider),
            openAiKey = p.openAiApiKey,
            openAiModel = p.openAiModel,
            openAiBaseUrl = p.openAiBaseUrl,
            anthropicKey = p.anthropicApiKey,
            anthropicModel = p.anthropicModel,
            geminiKey = p.geminiApiKey,
            geminiModel = p.geminiModel,
            ollamaModel = p.ollamaModel,
            ollamaBaseUrl = p.ollamaBaseUrl,
            maxSteps = p.maxSteps,
            debugMode = p.debugMode,
            confirmMessages = p.confirmMessages,
            confirmCalls = p.confirmCalls,
            confirmPayments = p.confirmPayments,
            confirmDeletions = p.confirmDeletions,
            confirmSystemSettings = p.confirmSystemSettings,
            confirmPermissions = p.confirmPermissions,
            unrestrictedMode = p.unrestrictedMode,
            useAllowlist = p.useAllowlist,
            allowlistPackages = p.allowlistPackages,
            blocklistPackages = p.blocklistPackages,
            showFloatingOverlay = p.showFloatingOverlay,
            decisionBackend = p.decisionBackend,
            layaServerUrl = p.layaServerUrl,
            watchPollSeconds = p.watchPollSeconds
        )
    }

    fun save(context: Context) {
        val p = prefs ?: SecurePreferences.getInstance(context)
        val s = _uiState.value
        p.selectedProvider = s.selectedProvider.name
        p.openAiApiKey = s.openAiKey
        p.openAiModel = s.openAiModel
        p.openAiBaseUrl = s.openAiBaseUrl
        p.anthropicApiKey = s.anthropicKey
        p.anthropicModel = s.anthropicModel
        p.geminiApiKey = s.geminiKey
        p.geminiModel = s.geminiModel
        p.ollamaModel = s.ollamaModel
        p.ollamaBaseUrl = s.ollamaBaseUrl
        p.maxSteps = s.maxSteps
        p.debugMode = s.debugMode
        p.confirmMessages = s.confirmMessages
        p.confirmCalls = s.confirmCalls
        p.confirmPayments = s.confirmPayments
        p.confirmDeletions = s.confirmDeletions
        p.confirmSystemSettings = s.confirmSystemSettings
        p.confirmPermissions = s.confirmPermissions
        p.unrestrictedMode = s.unrestrictedMode
        p.useAllowlist = s.useAllowlist
        p.allowlistPackages = s.allowlistPackages
        p.blocklistPackages = s.blocklistPackages
        p.showFloatingOverlay = s.showFloatingOverlay
        p.decisionBackend = s.decisionBackend
        p.layaServerUrl = s.layaServerUrl
        p.watchPollSeconds = s.watchPollSeconds
    }

    fun updateProvider(provider: LlmProviderType) {
        _uiState.value = _uiState.value.copy(selectedProvider = provider)
    }

    fun updateOpenAiKey(key: String) { _uiState.value = _uiState.value.copy(openAiKey = key) }
    fun updateOpenAiModel(m: String) { _uiState.value = _uiState.value.copy(openAiModel = m) }
    fun updateOpenAiBaseUrl(u: String) { _uiState.value = _uiState.value.copy(openAiBaseUrl = u) }
    fun updateAnthropicKey(key: String) { _uiState.value = _uiState.value.copy(anthropicKey = key) }
    fun updateAnthropicModel(m: String) { _uiState.value = _uiState.value.copy(anthropicModel = m) }
    fun updateGeminiKey(key: String) { _uiState.value = _uiState.value.copy(geminiKey = key) }
    fun updateGeminiModel(m: String) { _uiState.value = _uiState.value.copy(geminiModel = m) }
    fun updateOllamaModel(m: String) { _uiState.value = _uiState.value.copy(ollamaModel = m) }
    fun updateOllamaBaseUrl(u: String) { _uiState.value = _uiState.value.copy(ollamaBaseUrl = u) }
    fun updateMaxSteps(steps: Int) {
        _uiState.value = _uiState.value.copy(
            maxSteps = steps.coerceIn(5, com.androclaw.agent.agent.AgentLoop.HARD_MAX_STEPS)
        )
    }
    fun updateDebugMode(enabled: Boolean) { _uiState.value = _uiState.value.copy(debugMode = enabled) }
    fun updateConfirmMessages(v: Boolean) { _uiState.value = _uiState.value.copy(confirmMessages = v) }
    fun updateConfirmCalls(v: Boolean) { _uiState.value = _uiState.value.copy(confirmCalls = v) }
    fun updateConfirmPayments(v: Boolean) { _uiState.value = _uiState.value.copy(confirmPayments = v) }
    fun updateConfirmDeletions(v: Boolean) { _uiState.value = _uiState.value.copy(confirmDeletions = v) }
    fun updateConfirmSystemSettings(v: Boolean) { _uiState.value = _uiState.value.copy(confirmSystemSettings = v) }
    fun updateConfirmPermissions(v: Boolean) { _uiState.value = _uiState.value.copy(confirmPermissions = v) }
    fun updateUnrestrictedMode(v: Boolean) { _uiState.value = _uiState.value.copy(unrestrictedMode = v) }
    fun updateUseAllowlist(v: Boolean) { _uiState.value = _uiState.value.copy(useAllowlist = v) }
    fun updateShowFloatingOverlay(v: Boolean) { _uiState.value = _uiState.value.copy(showFloatingOverlay = v) }
    fun updateDecisionBackend(v: String) { _uiState.value = _uiState.value.copy(decisionBackend = v) }
    fun updateLayaServerUrl(v: String) { _uiState.value = _uiState.value.copy(layaServerUrl = v) }
    fun updateWatchPollSeconds(v: Int) {
        _uiState.value = _uiState.value.copy(watchPollSeconds = v.coerceIn(5, 120))
    }
}
