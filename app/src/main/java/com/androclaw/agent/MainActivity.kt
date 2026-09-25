package com.androclaw.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.androclaw.agent.data.SecurePreferences
import com.androclaw.agent.ui.chat.ChatScreen
import com.androclaw.agent.ui.onboarding.OnboardingScreen
import com.androclaw.agent.ui.settings.SettingsScreen
import com.androclaw.agent.ui.theme.AndroClawTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = SecurePreferences.getInstance(applicationContext)

        setContent {
            AndroClawTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()

                    val startDest = if (prefs.onboardingComplete) "chat" else "onboarding"

                    NavHost(
                        navController = navController,
                        startDestination = startDest
                    ) {
                        composable("onboarding") {
                            OnboardingScreen(
                                onComplete = {
                                    prefs.onboardingComplete = true
                                    navController.navigate("chat") {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("chat") {
                            ChatScreen(
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
