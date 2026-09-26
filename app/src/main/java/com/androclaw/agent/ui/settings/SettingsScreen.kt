package com.androclaw.agent.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.androclaw.agent.data.LlmProviderType
import com.androclaw.agent.data.SecurePreferences
import com.androclaw.agent.ui.overlay.FloatingOverlayService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.save(context)
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        viewModel.save(context)
                        onBack()
                    }) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Provider selection
            SectionHeader("LLM Provider")
            ProviderSelector(
                selected = state.selectedProvider,
                onSelect = viewModel::updateProvider
            )

            // Provider-specific settings
            when (state.selectedProvider) {
                LlmProviderType.OPENAI -> {
                    ApiKeyField("OpenAI API Key", state.openAiKey, viewModel::updateOpenAiKey)
                    TextSettingField("Model", state.openAiModel, viewModel::updateOpenAiModel)
                    TextSettingField("Base URL", state.openAiBaseUrl, viewModel::updateOpenAiBaseUrl)
                }
                LlmProviderType.ANTHROPIC -> {
                    ApiKeyField("Anthropic API Key", state.anthropicKey, viewModel::updateAnthropicKey)
                    TextSettingField("Model", state.anthropicModel, viewModel::updateAnthropicModel)
                }
                LlmProviderType.GEMINI -> {
                    ApiKeyField("Gemini API Key", state.geminiKey, viewModel::updateGeminiKey)
                    TextSettingField("Model", state.geminiModel, viewModel::updateGeminiModel)
                }
                LlmProviderType.OLLAMA -> {
                    TextSettingField("Model", state.ollamaModel, viewModel::updateOllamaModel)
                    TextSettingField("Base URL", state.ollamaBaseUrl, viewModel::updateOllamaBaseUrl)
                }
            }

            HorizontalDivider()

            // System 1 decision backend
            SectionHeader("Decision Backend")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.decisionBackend == SecurePreferences.DECISION_BACKEND_OFF,
                    onClick = { viewModel.updateDecisionBackend(SecurePreferences.DECISION_BACKEND_OFF) },
                    label = { Text("Off") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = state.decisionBackend == SecurePreferences.DECISION_BACKEND_LAYA,
                    onClick = { viewModel.updateDecisionBackend(SecurePreferences.DECISION_BACKEND_LAYA) },
                    label = { Text("Laya server") },
                    modifier = Modifier.weight(1f)
                )
            }
            if (state.decisionBackend == SecurePreferences.DECISION_BACKEND_LAYA) {
                TextSettingField("Laya server URL", state.layaServerUrl, viewModel::updateLayaServerUrl)
            }
            Text(
                "Sends UI text to your server on your network. No cloud involved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // Agent behavior
            SectionHeader("Agent Behavior")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Max Steps", style = MaterialTheme.typography.bodyLarge)
                    Text("${state.maxSteps} steps per task",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.updateMaxSteps(state.maxSteps - 5) }) {
                        Icon(Icons.Default.Remove, null)
                    }
                    Text("${state.maxSteps}", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { viewModel.updateMaxSteps(state.maxSteps + 5) }) {
                        Icon(Icons.Default.Add, null)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Watch Poll Interval", style = MaterialTheme.typography.bodyLarge)
                    Text("${state.watchPollSeconds}s between chat checks while watching",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.updateWatchPollSeconds(state.watchPollSeconds - 5) }) {
                        Icon(Icons.Default.Remove, null)
                    }
                    Text("${state.watchPollSeconds}s", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { viewModel.updateWatchPollSeconds(state.watchPollSeconds + 5) }) {
                        Icon(Icons.Default.Add, null)
                    }
                }
            }

            SwitchSettingRow(
                title = "Debug Mode",
                subtitle = "Log UI trees and screenshots (privacy: keep off)",
                checked = state.debugMode,
                onCheckedChange = viewModel::updateDebugMode
            )

            HorizontalDivider()

            // Confirmation policies & Unrestricted Mode
            SectionHeader("Access & Safety Controls")
            SwitchSettingRow(
                title = "Unrestricted Mode",
                subtitle = "Allow agent full access to all apps without confirmation prompts",
                checked = state.unrestrictedMode,
                onCheckedChange = viewModel::updateUnrestrictedMode
            )

            if (!state.unrestrictedMode) {
                Text(
                    "AndroClaw will ask before performing these actions:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SwitchSettingRow("Send Messages", "SMS, email, chat", state.confirmMessages, viewModel::updateConfirmMessages)
                SwitchSettingRow("Make Calls", "Phone and video calls", state.confirmCalls, viewModel::updateConfirmCalls)
                SwitchSettingRow("Payments", "Payment and banking apps", state.confirmPayments, viewModel::updateConfirmPayments)
                SwitchSettingRow("Install Apps", "Install, update, or download apps", state.confirmAppInstall, viewModel::updateConfirmAppInstall)
                SwitchSettingRow("Delete Data", "Delete, clear, erase actions", state.confirmDeletions, viewModel::updateConfirmDeletions)
                SwitchSettingRow("System Settings", "Changing device settings", state.confirmSystemSettings, viewModel::updateConfirmSystemSettings)
                SwitchSettingRow("Grant Permissions", "Allowing app permissions", state.confirmPermissions, viewModel::updateConfirmPermissions)
            }

            HorizontalDivider()

            // App filter
            SectionHeader("App Filter")
            SwitchSettingRow(
                title = "Allowlist Mode",
                subtitle = "Only operate in explicitly listed apps",
                checked = state.useAllowlist,
                onCheckedChange = viewModel::updateUseAllowlist
            )
            SwitchSettingRow(
                title = "Floating Overlay",
                subtitle = "Enable a floating button to summon the agent from any app",
                checked = state.showFloatingOverlay,
                onCheckedChange = {
                    viewModel.updateShowFloatingOverlay(it)
                    // Toggle service here
                    val intent = Intent(context, FloatingOverlayService::class.java)
                    if (it) {
                        context.startService(intent)
                    } else {
                        context.stopService(intent)
                    }
                }
            )
            if (state.blocklistPackages.isNotEmpty()) {
                Text(
                    "Blocked: ${state.blocklistPackages.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
fun ProviderSelector(
    selected: LlmProviderType,
    onSelect: (LlmProviderType) -> Unit
) {
    val providers = LlmProviderType.values()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        providers.forEach { provider ->
            FilterChip(
                selected = selected == provider,
                onClick = { onSelect(provider) },
                label = { Text(provider.name.lowercase().replaceFirstChar { it.uppercase() }) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun ApiKeyField(label: String, value: String, onValueChange: (String) -> Unit) {
    var showKey by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    null
                )
            }
        },
        singleLine = true
    )
}

@Composable
fun TextSettingField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
