package com.jsaiborne.vocalpitchdetector.ui.theme

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

// Professional Audio Palette
private val DarkTealPrimary = Color(0xFF4DB6AC)
private val DarkTealSecondary = Color(0xFF00796B)
private val MidnightBackground = Color(0xFF0B0E0E)
private val DeepSurface = Color(0xFF1A1C1C)

private val DarkColorScheme = darkColorScheme(
    primary = DarkTealPrimary,
    secondary = DarkTealSecondary,
    tertiary = Color(0xFFFFB74D), // Warm accent for highlights
    background = MidnightBackground,
    surface = DeepSurface,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onTertiary = Color.Black,
    onBackground = Color(0xFFE1E3E3),
    onSurface = Color(0xFFE1E3E3),
    surfaceVariant = Color(0xFF3F4948) // For cards and sliders
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006A60),
    secondary = Color(0xFF4A635F),
    tertiary = Color(0xFF825500),
    background = Color(0xFFFBFDFA),
    surface = Color(0xFFFBFDFA),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF191C1B),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5E3)
)

@Composable
fun VocalPitchdetectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Set to false for a consistent "branded" look for an instrument app
    dynamicColor: Boolean = false,
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
        typography = Typography,
        content = content
    )
}
