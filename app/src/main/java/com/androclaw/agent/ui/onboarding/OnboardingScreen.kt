package com.androclaw.agent.ui.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    var currentPage by remember { mutableStateOf(0) }
    val pages = listOf(
        OnboardingPage(
            icon = Icons.Default.SmartToy,
            title = "Welcome to AndroClaw",
            body = "AndroClaw is an AI agent that can control your phone to complete tasks you describe in natural language — across any app."
        ),
        OnboardingPage(
            icon = Icons.Default.Visibility,
            title = "What it sees",
            body = "AndroClaw reads the accessibility tree — a structural description of what's on your screen (text, buttons, input fields). It does NOT capture raw screenshots by default. Screen content is processed in-memory only."
        ),
        OnboardingPage(
            icon = Icons.Default.TouchApp,
            title = "What it can do",
            body = "AndroClaw can tap buttons, type text, scroll, swipe, open apps, and perform system actions like pressing Back or Home — all on your behalf."
        ),
        OnboardingPage(
            icon = Icons.Default.Security,
            title = "Your safety & privacy",
            body = "Sensitive actions (sending messages, making calls, payments) require your confirmation. You can block specific apps. API keys are stored encrypted. No data is sent anywhere except the LLM provider you configure."
        ),
        OnboardingPage(
            icon = Icons.Default.Accessibility,
            title = "Enable the service",
            body = "AndroClaw requires the Accessibility Service to be enabled. Tap the button below, find AndroClaw in the list, and toggle it on."
        )
    )

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            val page = pages[currentPage]
            Icon(
                page.icon, null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(24.dp))
            Text(
                page.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
            Text(
                page.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.weight(1f))

            // Page indicators
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.indices.forEach { i ->
                    val size = if (i == currentPage) 12.dp else 8.dp
                    Surface(
                        modifier = Modifier.size(size),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = if (i == currentPage) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    ) {}
                }
            }
            Spacer(Modifier.height(24.dp))

            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (currentPage > 0) {
                    OutlinedButton(onClick = { currentPage-- }) { Text("Back") }
                } else {
                    Spacer(Modifier.size(1.dp))
                }

                if (currentPage < pages.size - 1) {
                    Button(onClick = { currentPage++ }) { Text("Next") }
                } else {
                    // Last page: enable accessibility + done
                    Column(horizontalAlignment = Alignment.End) {
                        Button(onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }) {
                            Icon(Icons.Default.OpenInNew, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open Accessibility Settings")
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onComplete) {
                            Text("I've enabled it, continue →")
                        }
                    }
                }
            }
        }
    }
}

data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val body: String
)
