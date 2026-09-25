package com.androclaw.agent.ui.chat

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.androclaw.agent.agent.AgentState
import com.androclaw.agent.data.TaskEntity
import com.androclaw.agent.data.TaskStatus
import com.androclaw.agent.safety.ConfirmationRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showSaveRoutineDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.bindService(context)
        viewModel.loadHistory(context)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.unbindService(context) }
    }

    // Accessibility banner
    if (!uiState.isAccessibilityEnabled) {
        AccessibilityBanner {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            context.startActivity(intent)
        }
        return
    }

    // Confirmation dialog
    uiState.pendingConfirmation?.let { req ->
        ConfirmationDialog(
            request = req,
            onConfirm = { viewModel.confirmAction() },
            onCancel = { viewModel.cancelAction() }
        )
    }

    // Save routine dialog
    if (showSaveRoutineDialog) {
        SaveRoutineDialog(
            onSave = { name, phrase ->
                viewModel.saveAsRoutine(context, name, phrase)
                showSaveRoutineDialog = false
            },
            onDismiss = { showSaveRoutineDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("AndroClaw", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, "History")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                inputText = uiState.inputText,
                onTextChange = viewModel::onInputChanged,
                onSend = { viewModel.sendTask(context) },
                isRunning = uiState.agentState is AgentState.Executing ||
                        uiState.agentState is AgentState.Planning,
                onStop = viewModel::stopTask
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Agent state card
            AgentStateCard(
                state = uiState.agentState,
                onStop = viewModel::stopTask,
                onSaveRoutine = { showSaveRoutineDialog = true }
            )

            // Task thread
            val listState = rememberLazyListState()
            val history = uiState.taskHistory

            LaunchedEffect(history.size) {
                if (history.isNotEmpty()) listState.animateScrollToItem(0)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                reverseLayout = true
            ) {
                // Current running task (if any)
                item {
                    CurrentTaskBubble(state = uiState.agentState)
                }

                // Historical tasks
                items(history, key = { it.id }) { task ->
                    TaskHistoryCard(task = task)
                }
            }
        }
    }
}

@Composable
fun AccessibilityBanner(onEnable: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Accessibility,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Accessibility Service Required",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "AndroClaw needs the accessibility service enabled to observe and interact with other apps. Tap below to open Settings.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onEnable) {
                    Icon(Icons.Default.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enable in Settings")
                }
            }
        }
    }
}

@Composable
fun AgentStateCard(
    state: AgentState,
    onStop: () -> Unit,
    onSaveRoutine: () -> Unit
) {
    val isRunning = state is AgentState.Executing || state is AgentState.Planning
    val isTerminal = state is AgentState.Completed || state is AgentState.Stopped ||
            state is AgentState.Failed

    AnimatedVisibility(
        visible = state !is AgentState.Idle,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (state) {
                    is AgentState.Completed -> MaterialTheme.colorScheme.primaryContainer
                    is AgentState.Failed -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            isRunning -> {
                                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                val alpha by infiniteTransition.animateFloat(
                                    initialValue = 1f, targetValue = 0.3f,
                                    animationSpec = infiniteRepeatable(
                                        tween(600, easing = LinearEasing),
                                        RepeatMode.Reverse
                                    ),
                                    label = "pulseAlpha"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .alpha(alpha)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                            }
                            state is AgentState.Completed ->
                                Icon(Icons.Default.CheckCircle, null,
                                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            state is AgentState.Failed ->
                                Icon(Icons.Default.Error, null,
                                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            else ->
                                Icon(Icons.Default.Stop, null, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when (state) {
                                is AgentState.Planning -> "Planning..."
                                is AgentState.Executing -> "Step ${state.currentStep + 1}/${state.maxSteps}"
                                is AgentState.Completed -> "Done ✓"
                                is AgentState.Failed -> "Failed"
                                is AgentState.Stopped -> "Stopped"
                                is AgentState.WaitingForConfirmation -> "Waiting for input"
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (isRunning) {
                        FilledTonalButton(
                            onClick = onStop,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("STOP", fontSize = 12.sp)
                        }
                    }

                    if (isTerminal && (state is AgentState.Completed || state is AgentState.Stopped)) {
                        TextButton(onClick = onSaveRoutine) {
                            Icon(Icons.Default.Bookmark, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save", fontSize = 12.sp)
                        }
                    }
                }

                // Goal display
                val goal = when (state) {
                    is AgentState.Planning -> state.goal
                    is AgentState.Executing -> state.goal
                    is AgentState.Completed -> state.goal
                    is AgentState.Failed -> state.goal
                    is AgentState.Stopped -> state.goal
                    is AgentState.WaitingForConfirmation -> state.goal
                    else -> null
                }
                goal?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Step narrations
                val steps = when (state) {
                    is AgentState.Executing -> state.steps
                    is AgentState.Completed -> state.steps
                    is AgentState.Failed -> state.steps
                    is AgentState.Stopped -> state.steps
                    is AgentState.WaitingForConfirmation -> state.steps
                    else -> emptyList()
                }
                if (steps.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    steps.takeLast(3).forEach { step ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("${step.stepIndex + 1}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(24.dp))
                            Text(step.narration,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // Failure/completion messages
                when (state) {
                    is AgentState.Failed -> {
                        Spacer(Modifier.height(4.dp))
                        Text(state.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    is AgentState.Completed -> {
                        Spacer(Modifier.height(4.dp))
                        Text(state.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun CurrentTaskBubble(state: AgentState) {
    // Shown inline in the chat thread only when in a running state
    if (state is AgentState.Idle) return
    // The AgentStateCard above handles the full display
}

@Composable
fun TaskHistoryCard(task: TaskEntity) {
    val statusColor = when (task.status) {
        TaskStatus.COMPLETED.name -> MaterialTheme.colorScheme.primary
        TaskStatus.FAILED.name -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusIcon = when (task.status) {
        TaskStatus.COMPLETED.name -> Icons.Default.CheckCircle
        TaskStatus.FAILED.name -> Icons.Default.Cancel
        TaskStatus.STOPPED.name -> Icons.Default.Stop
        else -> Icons.Default.HourglassEmpty
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(statusIcon, null, tint = statusColor,
                modifier = Modifier.padding(top = 2.dp).size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.goal,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(task.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (task.errorMessage != null) {
                    Text(
                        task.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${task.steps.size} steps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ChatInputBar(
    inputText: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isRunning: Boolean,
    onStop: () -> Unit
) {
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a task...", style = MaterialTheme.typography.bodyMedium) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 3,
                enabled = !isRunning
            )
            Spacer(Modifier.width(8.dp))
            if (isRunning) {
                FloatingActionButton(
                    onClick = onStop,
                    containerColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Stop, "Stop", tint = MaterialTheme.colorScheme.onError)
                }
            } else {
                FloatingActionButton(
                    onClick = { if (inputText.isNotBlank()) onSend() },
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Send, "Send", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
fun ConfirmationDialog(
    request: ConfirmationRequest,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Sensitive Action") },
        text = {
            Column {
                Text("Category: ${request.category.displayName}")
                Spacer(Modifier.height(4.dp))
                Text(request.category.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (request.nodeLabel.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Action: \"${request.nodeLabel}\" in ${request.packageName}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) { Text("Allow") }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

@Composable
fun SaveRoutineDialog(
    onSave: (name: String, triggerPhrase: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var triggerPhrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as Routine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Routine Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = triggerPhrase,
                    onValueChange = { triggerPhrase = it },
                    label = { Text("Trigger Phrase") },
                    placeholder = { Text("e.g. \"Turn on dark mode\"") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onSave(name, triggerPhrase) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
