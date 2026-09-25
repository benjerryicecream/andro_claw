package com.androclaw.agent.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val NavyDark = Color(0xFF1A1A2E)
val Crimson = Color(0xFFE94560)
val CrimsonLight = Color(0xFFFF7089)
val SurfaceDark = Color(0xFF16213E)
val OnSurfaceDark = Color(0xFFE0E0E0)
val ContainerDark = Color(0xFF0F3460)

private val DarkColorScheme = darkColorScheme(
    primary = Crimson,
    onPrimary = Color.White,
    primaryContainer = ContainerDark,
    onPrimaryContainer = Color.White,
    secondary = CrimsonLight,
    onSecondary = Color.White,
    background = NavyDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = ContainerDark,
    onSurfaceVariant = Color(0xFFB0B0C0)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFB22942),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDADB),
    onPrimaryContainer = Color(0xFF3B0010),
    secondary = Crimson,
    onSecondary = Color.White,
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF1A1A2E),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF1A1A2E),
    surfaceVariant = Color(0xFFF3DEE0),
    onSurfaceVariant = Color(0xFF4D3738)
)

@Composable
fun AndroClawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
