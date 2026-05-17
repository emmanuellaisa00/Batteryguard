package com.laisadevstudio.batteryguard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color.Black,
    background = Color(0xFF0A0A1A),
    surface = Color(0xFF1E2A3A),
    onBackground = Color.White,
    onSurface = Color.White,
    secondary = Color(0xFFFF6B35),
    error = Color(0xFFFF1744)
)

@Composable
fun BatteryGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
